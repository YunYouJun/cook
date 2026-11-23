package cn.yunyoujun.cook.data.remote

import android.net.Uri
import android.os.SystemClock
import androidx.room.withTransaction
import cn.yunyoujun.cook.core.ai.LocalSearchIntentParser
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.ai.SearchIntentException
import cn.yunyoujun.cook.core.ai.SearchIntentSource
import cn.yunyoujun.cook.core.ai.SearchIntentValidator
import cn.yunyoujun.cook.core.ai.StepGuidanceException
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.ai.StepGuidanceResponse
import cn.yunyoujun.cook.core.ai.StepGuidanceValidator
import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.membership.MembershipAccountToken
import cn.yunyoujun.cook.core.model.DietaryGoal
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.data.local.AccountFavoriteEntity
import cn.yunyoujun.cook.data.local.AccountSyncStateEntity
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext

/**
 * 与 @yunlefun/sso 的 SSO v3 协议一致；不接收 URL 中的会话或长期凭据。
 * 1:1 对齐 iOS `YunlefunSSO.swift`。
 */
@Serializable
class YunlefunSSO(
    val createdAtMs: Long = System.currentTimeMillis(),
    val nonce: String = randomToken(),
    val verifier: String = randomToken(),
    internal val inApp: Boolean = false,
) {
    sealed interface Callback {
        data class Authorized(val fields: Map<String, String>) : Callback
        data object Denied : Callback
        data object SelectAccountOnWeb : Callback

        fun exchangeFields(): Map<String, String> = when (this) {
            is Authorized -> fields
            Denied -> throw CancellationException("User cancelled SSO")
            SelectAccountOnWeb -> throw YunlefunAccountException.AuthorizationFailed()
        }
    }

    val authorizationUrl: String
        get() = buildAuthorizationUrl("/auth/sso")

    val appAuthorizationUrl: String
        get() = buildAuthorizationUrl("/auth/app")

    private fun buildAuthorizationUrl(path: String): String {
        val challenge = sha256Base64Url(verifier.toByteArray(Charsets.UTF_8))
        val params = listOf(
            "client_id" to CLIENT_ID,
            "redirect_uri" to REDIRECT_URI,
            "scope" to SCOPE,
            "nonce" to nonce,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "prompt" to "select_account",
        ).joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$ISSUER$path?$params"
    }

    internal fun isCurrentAt(nowMs: Long): Boolean =
        nowMs >= createdAtMs && nowMs - createdAtMs in 0 until LIFETIME_MS

    fun callbackResult(callbackUrl: String, nowMs: Long = System.currentTimeMillis()): Callback {
        if (!isCurrentAt(nowMs)) {
            throw YunlefunAccountException.InvalidCallback()
        }
        if (!isCallback(callbackUrl)) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val fragment = runCatching { URI(callbackUrl).rawFragment }.getOrNull()
            ?: throw YunlefunAccountException.InvalidCallback()
        if (!fragment.startsWith(CALLBACK_RESULT_PREFIX) || fragment.length >= MAX_CALLBACK_FRAGMENT_LENGTH) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val encoded = fragment.removePrefix(CALLBACK_RESULT_PREFIX)
        val decodedBytes = decodeBase64Url(encoded) ?: throw YunlefunAccountException.InvalidCallback()
        val payload = runCatching {
            Json.parseToJsonElement(decodedBytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrElse { throw YunlefunAccountException.InvalidCallback() }

        if (!ALLOWED_FRAGMENT_KEYS.containsAll(payload.keys)) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val payloadNonce = (payload["nonce"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val payloadIss = (payload["iss"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val ok = (payload["ok"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        if (payloadNonce != nonce || payloadIss != ISSUER || ok == null) {
            throw YunlefunAccountException.InvalidCallback()
        }
        if (!ok) {
            if (payload["code"] != null) {
                throw YunlefunAccountException.InvalidCallback()
            }
            return when ((payload["reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "access_denied" -> Callback.Denied
                "account_selection_required" -> Callback.SelectAccountOnWeb
                else -> throw YunlefunAccountException.AuthorizationFailed()
            }
        }
        if (payload["reason"] != null) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val code = (payload["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw YunlefunAccountException.InvalidCallback()
        if (!CODE_REGEX.matches(code)) {
            throw YunlefunAccountException.InvalidCallback()
        }
        return Callback.Authorized(
            mapOf(
                "action" to "exchangeSsoCode",
                "clientId" to CLIENT_ID,
                "redirectUri" to REDIRECT_URI,
                "scope" to SCOPE,
                "code" to code,
                "nonce" to nonce,
                "codeVerifier" to verifier,
            ),
        )
    }

    fun exchangeFields(callbackUrl: String, nowMs: Long = System.currentTimeMillis()): Map<String, String> =
        callbackResult(callbackUrl, nowMs).exchangeFields()

    internal enum class CallbackTarget { NATIVE, WEB }

    companion object {
        const val ISSUER = "https://www.yunle.fun"
        const val CLIENT_ID = "cook-mobile"
        internal const val CALLBACK_HOST = "cook.yunyoujun.cn"
        const val REDIRECT_URI = "https://$CALLBACK_HOST/auth/callback?platform=native"
        const val SCOPE = "identity:bootstrap"
        const val LIFETIME_MS = 600_000L

        private const val CALLBACK_RESULT_PREFIX = "ylf_sso="
        private const val MAX_CALLBACK_FRAGMENT_LENGTH = 4096
        internal val MAX_CALLBACK_LENGTH = REDIRECT_URI.length + MAX_CALLBACK_FRAGMENT_LENGTH
        private val ALLOWED_FRAGMENT_KEYS = setOf("nonce", "iss", "ok", "code", "reason")
        private val CODE_REGEX = Regex("^[A-Za-z0-9_-]{43}$")
        private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val secureRandom = SecureRandom()

        fun isCallback(rawUrl: String): Boolean {
            val withoutFragment = rawUrl.substringBefore('#')
            return withoutFragment == REDIRECT_URI
        }

        internal fun isSignInCallback(rawUrl: String): Boolean =
            isCallback(rawUrl) && rawUrl.substringAfter('#', "").startsWith(CALLBACK_RESULT_PREFIX)

        internal fun callbackTarget(rawUrl: String): CallbackTarget? {
            val url = runCatching { URI(rawUrl) }.getOrNull() ?: return null
            if (!url.scheme.equals("https", ignoreCase = true) ||
                !url.host.equals(CALLBACK_HOST, ignoreCase = true) ||
                url.rawUserInfo != null || url.port !in listOf(-1, 443) ||
                url.rawPath != "/auth/callback"
            ) return null
            val targetsNative = url.rawQuery?.split('&')?.any { item ->
                Uri.decode(item.substringBefore('=')) == "platform" &&
                    Uri.decode(item.substringAfter('=', "")) == "native"
            } == true
            return if (targetsNative) CallbackTarget.NATIVE else CallbackTarget.WEB
        }

        fun randomToken(): String {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            return encodeBase64Url(bytes)
        }

        fun sha256Base64Url(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return encodeBase64Url(digest)
        }

        fun encodeBase64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun decodeBase64Url(value: String): ByteArray? {
            if (value.isEmpty() || !BASE64URL_REGEX.matches(value)) return null
            return runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
        }
    }
}

sealed class YunlefunAccountException(message: String) : Exception(message) {
    class InvalidCallback : YunlefunAccountException("授权结果无效或已过期，请重新登录。")
    class InvalidWebsiteCallback : YunlefunAccountException("账号管理授权无效或已过期，请重新打开账号管理。")
    class CheckoutUnavailable : YunlefunAccountException("当前无法打开云乐坊会员购买入口，请稍后重试。")
    class AuthorizationFailed : YunlefunAccountException("云乐坊授权未完成，请重新登录。")
    class InvalidResponse : YunlefunAccountException("登录服务返回了无效结果，请稍后重试。")
    class ExpiredSession : YunlefunAccountException("登录已过期，请重新登录。")
    class SecureStorage : YunlefunAccountException("无法访问安全存储，请解锁设备后重试。")
    class Unavailable(val retryable: Boolean = true) : YunlefunAccountException("暂时无法连接登录服务，请稍后重试。")
}

@Serializable
data class YunlefunAccount(
    @SerialName("sub") val id: String,
    val name: String? = null,
    val username: String? = null,
    val status: String? = null,
    @SerialName("is_anonymous") val isAnonymous: Boolean? = null,
    val picture: String? = null,
) {
    val displayName: String
        get() = listOfNotNull(name, username)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() } ?: "云乐坊用户"

    val hasUsableStatus: Boolean
        get() {
            val s = status ?: return true
            return s.uppercase() in setOf("DEFAULT", "ACTIVE")
        }
}

@Serializable
data class YunlefunCredential(
    val accessToken: String,
    val refreshToken: String,
    val subject: String,
    val expiresAtEpochMs: Long,
    val deviceID: String,
) {
    fun isValid(nowMs: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochMs > nowMs &&
            subject.isNotEmpty() &&
            accessToken.isNotEmpty() &&
            accessToken.toByteArray(Charsets.UTF_8).size <= 16_384 &&
            accessToken.all { it.code in 33..126 }
}

/**
 * 仅访问固定 HTTPS 端点，不接收重定向，不共享 Cookie，也不记录凭据。
 * 对齐 iOS `YunlefunAccountService.swift`。
 */
class YunlefunAccountService(
    private val transport: HttpTransport = IsolatedHttpPostTransport,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun signIn(fields: Map<String, String>, deviceID: String): YunlefunCredential {
        val bodyText = sendAccountRequest(
            url = TICKET_URL,
            method = "POST",
            fields = fields,
            origin = COOK_ORIGIN,
        )
        return exchangeTicket(bodyText, deviceID = deviceID)
    }

    internal suspend fun signInWithWeChat(code: String, deviceID: String): YunlefunCredential {
        validateWeChatCode(code)
        val response = weChatRequest("exchangeCookWeChatCode", mapOf("code" to code))
        return exchangeWeChatTicket(response, deviceID)
    }

    internal suspend fun bindWeChat(code: String, credential: YunlefunCredential) {
        validateWeChatCode(code)
        if (!credential.isValid()) throw YunlefunAccountException.ExpiredSession()
        val response = weChatRequest("bindCookWeChat", mapOf("code" to code), credential = credential)
        if (response.keys != setOf("ok")) throw YunlefunAccountException.InvalidResponse()
    }

    internal suspend fun weChatSignupCaptcha(deviceID: String): WeChatSignupCaptcha {
        val response = weChatRequest("cookSignupCaptcha", deviceID = deviceID)
        val image = response.requiredWeChatString("image", 200_050)
        return WeChatSignupCaptcha(image, response.requiredWeChatString("challenge", 4096))
    }

    internal suspend fun sendWeChatSignupSms(
        phone: String,
        captcha: Pair<String, String>?,
        deviceID: String,
    ): String {
        if (!WECHAT_SIGNUP_PHONE.matches(phone)) throw YunlefunAccountException.InvalidResponse()
        val fields = buildMap {
            put("phone", phone)
            if (captcha != null) {
                put("captchaChallenge", captcha.first)
                put("captchaAnswer", captcha.second)
            }
        }
        return weChatRequest("sendCookSignupSms", fields, deviceID)
            .requiredWeChatString("challenge", 4096)
    }

    internal suspend fun verifyWeChatSignupSms(challenge: String, otp: String, deviceID: String): WeChatSignupNext {
        if (!WECHAT_SIGNUP_OTP.matches(otp) || challenge.length !in 1..4096) {
            throw WeChatAccountException(WeChatAccountException.Reason.INVALID_OTP)
        }
        val response = weChatRequest("verifyCookSignupSms", mapOf("challenge" to challenge, "otp" to otp), deviceID)
        return when (response["next"]) {
            JsonPrimitive("existing") -> {
                if ("proof" in response) throw YunlefunAccountException.InvalidResponse()
                WeChatSignupNext.ExistingAccount
            }
            JsonPrimitive("confirm") -> WeChatSignupNext.Confirm(response.requiredWeChatString("proof", 4096))
            else -> throw YunlefunAccountException.InvalidResponse()
        }
    }

    internal suspend fun registerWithWeChat(proof: String, code: String, deviceID: String): YunlefunCredential {
        validateWeChatCode(code)
        if (proof.length !in 1..4096) throw WeChatAccountException(WeChatAccountException.Reason.RESTART_SIGNUP)
        val response = try {
            weChatRequest(WECHAT_REGISTER_ACTION, mapOf("proof" to proof, "code" to code), deviceID)
        } catch (error: YunlefunAccountException) {
            if (error !is YunlefunAccountException.Unavailable && error !is YunlefunAccountException.InvalidResponse) throw error
            throw WeChatAccountException(WeChatAccountException.Reason.SIGNUP_UNCONFIRMED)
        }
        return try {
            exchangeWeChatTicket(response, deviceID)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw WeChatAccountException(WeChatAccountException.Reason.ACCOUNT_CREATED)
        }
    }

    private suspend fun weChatRequest(
        action: String,
        fields: Map<String, String> = emptyMap(),
        deviceID: String? = null,
        credential: YunlefunCredential? = null,
    ): JsonObject {
        val data = sendAccountRequest(
            url = TICKET_URL,
            fields = fields + mapOf("action" to action, "clientId" to YunlefunSSO.CLIENT_ID),
            credential = credential,
            deviceID = deviceID,
            origin = COOK_ORIGIN,
            weChatAction = action,
        )
        val response = runCatching { Json.parseToJsonElement(data).jsonObject }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        if (response["ok"] != JsonPrimitive(true)) throw YunlefunAccountException.InvalidResponse()
        return response
    }

    private suspend fun exchangeWeChatTicket(
        payload: JsonObject,
        deviceID: String,
    ): YunlefunCredential {
        if (payload.keys != setOf("ok", "ticket", "subject")) throw YunlefunAccountException.InvalidResponse()
        val subject = payload.requiredWeChatString("subject", 512)
        val credential = exchangeCustomTicket(payload.requiredWeChatString("ticket", 32_768), deviceID)
        if (credential.subject != subject) {
            runCatching { signOut(credential) }
            throw YunlefunAccountException.InvalidResponse()
        }
        return credential
    }

    private fun JsonObject.requiredWeChatString(key: String, maxSize: Int): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf {
            it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= maxSize
        } ?: throw YunlefunAccountException.InvalidResponse()

    private fun validateWeChatCode(code: String) {
        if (!isWeChatAuthorizationCode(code)) throw WeChatAccountException(WeChatAccountException.Reason.AUTHORIZATION_FAILED)
    }

    suspend fun refresh(credential: YunlefunCredential): YunlefunCredential {
        val next = requestToken(
            path = "token",
            fields = mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to credential.refreshToken,
            ),
            deviceID = credential.deviceID,
        )
        if (next.subject != credential.subject) {
            throw YunlefunAccountException.ExpiredSession()
        }
        return next
    }

    suspend fun account(credential: YunlefunCredential): YunlefunAccount {
        val bodyText = sendAccountRequest(
            url = "${GATEWAY}user/me",
            method = "GET",
            credential = credential,
        )
        val user = runCatching { json.decodeFromString<YunlefunAccount>(bodyText) }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        if (user.id.isEmpty() || user.id != credential.subject || user.isAnonymous == true || !user.hasUsableStatus) {
            throw YunlefunAccountException.ExpiredSession()
        }
        return user
    }

    suspend fun signOut(credential: YunlefunCredential) {
        sendAccountRequest(
            url = "${GATEWAY}user/signout",
            method = "POST",
            fields = emptyMap(),
            credential = credential,
        )
    }

    suspend fun supportsAccountWebHandoff(): Boolean {
        val response = try {
            transport.request(
                method = "GET",
                url = YunlefunAccountWebHandoff.ENDPOINT,
                maxResponseBytes = 1024,
                connectTimeoutMs = 10_000,
                readTimeoutMs = 10_000,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw YunlefunAccountException.Unavailable()
        }
        if (response.statusCode == 404) return false
        if (response.statusCode != 200) throw YunlefunAccountException.Unavailable()
        val wire = runCatching { json.decodeFromString<WebHandoffCapability>(response.bodyText) }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        return wire.supported && wire.systemBrowser
    }

    suspend fun approveAccountWebHandoff(fields: Map<String, String>, credential: YunlefunCredential) {
        if (!credential.isValid()) throw YunlefunAccountException.ExpiredSession()
        val data = sendAccountRequest(
            url = TICKET_URL,
            fields = fields,
            credential = credential,
            origin = COOK_ORIGIN,
        )
        val approved = runCatching { json.decodeFromString<WebHandoffApproval>(data).ok }.getOrDefault(false)
        if (!approved) throw YunlefunAccountException.InvalidResponse()
    }

    @Serializable
    private data class WebHandoffCapability(val supported: Boolean, val systemBrowser: Boolean = false)

    @Serializable
    private data class WebHandoffApproval(val ok: Boolean)

    private suspend fun exchangeTicket(bodyText: String, deviceID: String): YunlefunCredential {
        val obj = runCatching { Json.parseToJsonElement(bodyText).jsonObject }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true
        val ticket = obj["ticket"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
        val forbiddenKeys = listOf("access_token", "refresh_token", "session")
        if (!ok || ticket.isEmpty() || forbiddenKeys.any { it in obj }) {
            throw YunlefunAccountException.InvalidResponse()
        }
        return exchangeCustomTicket(ticket, deviceID)
    }

    private suspend fun exchangeCustomTicket(ticket: String, deviceID: String): YunlefunCredential =
        requestToken(
            path = "signin/custom",
            fields = mapOf("provider_id" to "custom", "ticket" to ticket),
            deviceID = deviceID,
        )

    private suspend fun requestToken(
        path: String,
        fields: Map<String, String>,
        deviceID: String,
    ): YunlefunCredential {
        val bodyText = sendAccountRequest(
            url = GATEWAY + path,
            method = "POST",
            fields = fields,
            deviceID = deviceID,
        )
        val obj = runCatching { Json.parseToJsonElement(bodyText).jsonObject }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        val accessToken = obj["access_token"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
        val refreshToken = obj["refresh_token"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
        val subject = obj["sub"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
        val expiresInSec = obj["expires_in"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        if (accessToken.isEmpty() || refreshToken.isEmpty() || subject.isEmpty() ||
            expiresInSec <= 0.0 || expiresInSec > 31 * 86400.0
        ) {
            throw YunlefunAccountException.InvalidResponse()
        }
        return YunlefunCredential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            subject = subject,
            expiresAtEpochMs = System.currentTimeMillis() + (expiresInSec * 1000.0).toLong(),
            deviceID = deviceID,
        )
    }

    private suspend fun sendAccountRequest(
        url: String,
        method: String = "POST",
        fields: Map<String, String>? = null,
        credential: YunlefunCredential? = null,
        deviceID: String? = null,
        origin: String? = null,
        weChatAction: String? = null,
    ): String {
        val headers = LinkedHashMap<String, String>()
        (deviceID ?: credential?.deviceID)?.let { headers["x-device-id"] = it }
        origin?.let { headers["Origin"] = it }
        credential?.let { headers["Authorization"] = "Bearer ${it.accessToken}" }

        val jsonBody = fields?.let { map ->
            JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) }).toString()
        }
        val websiteApproval = url == TICKET_URL && fields?.get("action") == "approveCookWebHandoff"
        val response = try {
            transport.request(
                method = method,
                url = url,
                headers = headers,
                jsonBody = jsonBody,
                maxResponseBytes = 256 * 1024,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (error: Exception) {
            val retryable = !websiteApproval || (error is IOException && error !is SSLException)
            throw YunlefunAccountException.Unavailable(retryable)
        }

        if (response.statusCode !in 200..299) {
            if (weChatAction != null) {
                val reason = runCatching {
                    Json.parseToJsonElement(response.bodyText).jsonObject.requiredWeChatString("reason", 128)
                }.getOrNull()
                if (reason == "not_authenticated") throw YunlefunAccountException.ExpiredSession()
                val known = WeChatAccountException.Reason.entries.firstOrNull { it.wire != null && it.wire == reason }
                val signupOutcomeUnknown = weChatAction == WECHAT_REGISTER_ACTION && reason != "not_configured" &&
                    (response.statusCode == 408 || response.statusCode in 500..599)
                val fallback = if (signupOutcomeUnknown) {
                    WeChatAccountException.Reason.SIGNUP_UNCONFIRMED
                } else WeChatAccountException.Reason.UNAVAILABLE
                throw WeChatAccountException(known ?: fallback)
            }
            val failure = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }.getOrNull()
            val errCode = (failure?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (response.statusCode == 401 || errCode in setOf(
                    "invalid_grant", "invalid_token", "unauthenticated", "user_not_found", "user_disabled",
                )
            ) {
                throw YunlefunAccountException.ExpiredSession()
            }
            val reason = (failure?.get("reason") as? JsonPrimitive)?.content
            val retryable = !websiteApproval ||
                (response.statusCode in setOf(408, 500, 502, 503, 504) && reason != "not_configured")
            throw YunlefunAccountException.Unavailable(retryable)
        }
        return response.bodyText
    }

    companion object {
        private const val WECHAT_REGISTER_ACTION = "registerCookWeChat"
        const val GATEWAY = "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/auth/v1/"
        const val TICKET_URL = "https://api.yunle.fun/sso-ticket"
        const val COOK_ORIGIN = "https://cook.yunyoujun.cn"
    }
}

sealed class UserDataSyncException(message: String) : Exception(message) {
    class LoginRequired internal constructor(internal val canRefresh: Boolean = false) :
        UserDataSyncException("请重新登录后同步，离线记录已保留。")
    class MembershipRequired : UserDataSyncException("跨平台同步需要有效的云乐坊会员，已有记录仍可在本机使用。")
    class Unavailable : UserDataSyncException("暂时无法同步，恢复联网后会重试。")
    class InvalidResponse : UserDataSyncException("同步服务或数据版本暂不兼容，已停止覆盖，请稍后重试。")
    class LocalStorage : UserDataSyncException("账号记录未能保存，原有数据已保留。请检查设备储存空间。")
    class Disabled : UserDataSyncException("请先开启账号跨端同步。")
    class AccountCacheUnreadable : UserDataSyncException("账号缓存无法读取，暂时无法同步。")
    class LibraryUnreadable : UserDataSyncException("暂时无法读取当前收藏和历史库，请重试。")
    class LibraryChanged : UserDataSyncException("当前收藏与历史库已切换，请重试。")
}

/**
 * CloudBase 跨端用户数据同步服务 (`cook-user-data` v2 协议)。
 * 对齐 iOS `UserDataSyncService.swift`。
 */
class UserDataSyncService(
    private val transport: HttpTransport = IsolatedHttpPostTransport,
    private val endpoint: String = ENDPOINT,
) {
    suspend fun checkAccess(credential: YunlefunCredential) {
        sendSyncRequest(
            bodyJson = JsonObject(
                mapOf(
                    "action" to JsonPrimitive("capabilities"),
                    "ownerDigest" to JsonPrimitive(sha256Hex(credential.subject)),
                ),
            ).toString(),
            credential = credential,
        )
    }

    suspend fun sync(document: UserDataDocument, credential: YunlefunCredential): UserDataDocument {
        document.validate()
        val docElement = Json.parseToJsonElement(UserDataDocument.wireJson.encodeToString(document))
        val bodyObj = JsonObject(
            mapOf(
                "action" to JsonPrimitive("sync"),
                "document" to docElement,
                "ownerDigest" to JsonPrimitive(sha256Hex(credential.subject)),
            ),
        )
        val responseObj = sendSyncRequest(bodyObj.toString(), credential)
        val remoteDocElement = responseObj["document"] ?: throw UserDataSyncException.InvalidResponse()
        return try {
            UserDataDocument.decode(remoteDocElement.toString())
        } catch (_: Exception) {
            throw UserDataSyncException.InvalidResponse()
        }
    }

    private suspend fun sendSyncRequest(bodyJson: String, credential: YunlefunCredential): JsonObject {
        if (!credential.isValid()) {
            throw UserDataSyncException.LoginRequired()
        }
        val response = try {
            transport.request(
                method = "POST",
                url = endpoint,
                headers = mapOf("Authorization" to "Bearer ${credential.accessToken}"),
                jsonBody = bodyJson,
                maxResponseBytes = 300 * 1024,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: HttpResponseTooLargeException) {
            throw UserDataSyncException.InvalidResponse()
        } catch (_: Exception) {
            throw UserDataSyncException.Unavailable()
        }

        if (response.statusCode in 300..399) {
            throw UserDataSyncException.InvalidResponse()
        }
        if (response.statusCode == 408 || response.statusCode == 429 || response.statusCode in 500..599) {
            throw UserDataSyncException.Unavailable()
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }.getOrNull()
        val code = (body?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (code == "SESSION_CHANGED") throw UserDataSyncException.LoginRequired()
        if (response.statusCode == 401 || code == "LOGIN_REQUIRED") {
            throw UserDataSyncException.LoginRequired(canRefresh = true)
        }
        if (body == null) throw UserDataSyncException.InvalidResponse()
        if (code == "MEMBERSHIP_REQUIRED") {
            throw UserDataSyncException.MembershipRequired()
        }
        if (code in setOf("INVALID_REMOTE", "INVALID_DOCUMENT", "DOCUMENT_LIMIT", "UNKNOWN_ACTION")) {
            throw UserDataSyncException.InvalidResponse()
        }
        if (response.statusCode !in 200..299 || body["ok"] != JsonPrimitive(true)) {
            throw UserDataSyncException.Unavailable()
        }
        if (body["protocolVersion"] != JsonPrimitive(UserDataDocument.PROTOCOL_VERSION)) {
            throw UserDataSyncException.InvalidResponse()
        }
        return body
    }

    companion object {
        const val ENDPOINT = "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/v1/functions/cook-user-data"

        fun sha256Hex(input: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 本地与远程意图交叉校验，对齐 iOS `CookAIIntentValidation.swift`：
 * - 保留本地识别出的食材、忌口、厨具与饮食目标
 * - 拒绝不支持的医疗/减脂请求被模型擅自映射为内置饮食目标
 */
object CookAIIntentValidation {
    class ConflictsWithLocalException :
        Exception("AI 返回的饮食目标或厨具与原句冲突，请重试或使用本地识别。")

    private val UNSUPPORTED_HEALTH_REQUESTS = listOf(
        "控制体重", "减肥", "减脂", "生酮", "痘痘", "祛痘",
        "孕期", "孕妇", "糖尿病", "控糖", "血糖", "高血压", "降糖",
    )

    fun preserveLocalConditions(
        remote: SearchIntent,
        text: String,
        data: CookData,
        availableGoals: List<DietaryGoal>,
    ): SearchIntent {
        val validator = SearchIntentValidator(data, availableGoals)
        val validatedRemote = validator.validate(remote, expectedSource = SearchIntentSource.DEEPSEEK)
        val local = LocalSearchIntentParser(data, availableGoals).parse(text)

        val mergedGoal = if (local.goal != null) {
            if (validatedRemote.goal != null && validatedRemote.goal != local.goal) {
                throw ConflictsWithLocalException()
            }
            local.goal
        } else {
            validatedRemote.goal
        }

        val mergedTool = if (local.query.tool != null) {
            if (validatedRemote.query.tool != null && validatedRemote.query.tool != local.query.tool) {
                throw ConflictsWithLocalException()
            }
            local.query.tool
        } else {
            validatedRemote.query.tool
        }

        if (mergedGoal != null && local.goal == null) {
            val mentionsUnsupported = UNSUPPORTED_HEALTH_REQUESTS.any { text.contains(it) }
            val mentionsGoalTitle = DietaryGoal.entries.any { text.contains(it.title) }
            if (mentionsUnsupported || mentionsGoalTitle) {
                throw ConflictsWithLocalException()
            }
        }

        val mergedQuery = validatedRemote.query.copy(
            stuff = (local.query.stuff + validatedRemote.query.stuff).distinct(),
            avoid = (local.query.avoid + validatedRemote.query.avoid).distinct(),
            tool = mergedTool,
            noSpicy = local.query.noSpicy || validatedRemote.query.noSpicy,
            methodGroups = local.query.methodGroups.ifEmpty { validatedRemote.query.methodGroups },
            difficulty = local.query.difficulty.ifEmpty { validatedRemote.query.difficulty },
        )
        val mergedIntent = validatedRemote.copy(
            query = mergedQuery,
            goal = mergedGoal,
            unresolved = (local.unresolved + validatedRemote.unresolved).distinct(),
        )
        return validator.validate(mergedIntent, expectedSource = SearchIntentSource.DEEPSEEK)
    }
}

sealed class ManagedCookAIException(message: String) : Exception(message) {
    class Unconfigured : ManagedCookAIException("此版本尚未配置会员 AI 服务。")
    class LoginRequired : ManagedCookAIException("请登录云乐坊账号后检查会员资格。")
    class MembershipRequired : ManagedCookAIException("会员内置 DeepSeek 仅供有效云乐坊会员使用。")
    class AccountUnavailable : ManagedCookAIException("当前云乐坊账号暂时无法使用会员 AI 服务。")
    class RateLimited : ManagedCookAIException("会员 AI 请求较多，请稍后重试。")
    class BudgetExhausted : ManagedCookAIException("会员 AI 本期额度已用完，请稍后再试或使用自己的 Key。")
    class VersionMismatch : ManagedCookAIException("会员 AI 服务与当前菜谱版本不一致，请更新后重试。")
    class Unavailable : ManagedCookAIException("会员 AI 服务尚未就绪或暂时不可用，可以继续使用本地识别。")
    class InvalidResponse : ManagedCookAIException("会员 AI 未返回可用的条件，请重试或使用本地识别。")
    class TimedOut : ManagedCookAIException("会员 AI 识别超时，可以重试或使用本地识别。")
    class Offline : ManagedCookAIException("暂时无法连接会员 AI 服务，可以继续使用本地识别。")
    class InvalidInput : ManagedCookAIException("请输入 1–1000 字的做菜想法。")
}

data class ManagedCookAICapability(
    val enabled: Boolean,
    val membershipIsActive: Boolean,
    val membershipExpiresAtEpochMs: Long?,
)

/**
 * 会员托管版 AI 智能找菜 (`https://api.yunle.fun/cook-ai-intent`)。
 * 对齐 iOS `ManagedCookAIService.swift`。
 */
class ManagedCookAIService(
    rawEndpoint: String? = DEFAULT_ENDPOINT,
    private val transport: HttpTransport = IsolatedHttpPostTransport,
) {
    val endpoint: String? = rawEndpoint?.let(::approvedEndpoint)
    val isConfigured: Boolean get() = endpoint != null

    suspend fun capabilities(credential: YunlefunCredential): ManagedCookAICapability {
        val body = sendRequest(
            JsonObject(mapOf("action" to JsonPrimitive("capabilities"))).toString(),
            credential = credential,
        )
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ManagedCookAIException.InvalidResponse() }
        if (!ALLOWED_CAPABILITY_KEYS.containsAll(obj.keys)) {
            throw ManagedCookAIException.InvalidResponse()
        }
        val serviceVersion = obj["serviceVersion"]?.jsonPrimitive?.intOrNull
        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull
        val membership = obj["membership"]?.jsonObject
        if (serviceVersion != 1 || enabled == null || membership == null ||
            ! setOf("isActive", "expireAt").containsAll(membership.keys)
        ) {
            throw ManagedCookAIException.InvalidResponse()
        }
        val isActive = membership["isActive"]?.jsonPrimitive?.booleanOrNull
            ?: throw ManagedCookAIException.InvalidResponse()
        val expireAtMs = membership["expireAt"]?.jsonPrimitive?.doubleOrNull?.let { ms ->
            if (!ms.isFinite() || ms <= 0.0) throw ManagedCookAIException.InvalidResponse()
            ms.toLong()
        }
        if (enabled) {
            val code = obj["code"]?.jsonPrimitive?.takeIf { it.isString }?.content
            if (code != null || !isActive || expireAtMs == null || expireAtMs <= System.currentTimeMillis()) {
                throw ManagedCookAIException.InvalidResponse()
            }
        }
        return ManagedCookAICapability(
            enabled = enabled,
            membershipIsActive = isActive,
            membershipExpiresAtEpochMs = expireAtMs,
        )
    }

    suspend fun parse(
        text: String,
        credential: YunlefunCredential,
        data: CookData,
        availableGoals: List<DietaryGoal>,
    ): SearchIntent {
        if (text.trim().isEmpty() || text.length > 1_000) {
            throw ManagedCookAIException.InvalidInput()
        }
        val reqBody = JsonObject(
            mapOf(
                "action" to JsonPrimitive("parse"),
                "schemaVersion" to JsonPrimitive(SearchIntent.CURRENT_SCHEMA_VERSION),
                "policyVersion" to JsonPrimitive(SearchIntent.CURRENT_POLICY_VERSION),
                "dataVersion" to JsonPrimitive(data.meta.dataVersion),
                "text" to JsonPrimitive(text),
            ),
        ).toString()
        val body = sendRequest(reqBody, credential = credential)
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw ManagedCookAIException.InvalidResponse() }
        if (obj.keys != setOf("ok", "intent")) {
            throw ManagedCookAIException.InvalidResponse()
        }
        val rawIntent = obj["intent"]?.jsonObject ?: throw ManagedCookAIException.InvalidResponse()
        return try {
            val validator = SearchIntentValidator(data, availableGoals)
            val intent = validator.decode(rawIntent.toString(), expectedSource = SearchIntentSource.DEEPSEEK)
            CookAIIntentValidation.preserveLocalConditions(intent, text, data, availableGoals)
        } catch (_: SearchIntentException) {
            throw ManagedCookAIException.InvalidResponse()
        }
    }

    private suspend fun sendRequest(jsonBody: String, credential: YunlefunCredential): String {
        val url = endpoint ?: throw ManagedCookAIException.Unconfigured()
        if (!credential.isValid()) {
            throw ManagedCookAIException.LoginRequired()
        }
        val response = try {
            transport.request(
                method = "POST",
                url = url,
                headers = mapOf("Authorization" to "Bearer ${credential.accessToken}"),
                jsonBody = jsonBody,
                maxResponseBytes = 65_536,
                readTimeoutMs = 45_000,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw ManagedCookAIException.TimedOut()
        } catch (_: UnknownHostException) {
            throw ManagedCookAIException.Offline()
        } catch (_: Exception) {
            throw ManagedCookAIException.Unavailable()
        }

        val obj = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }.getOrNull()
        if (response.statusCode != 200) {
            val code = obj?.get("code")?.jsonPrimitive?.takeIf { it.isString }?.content
            throw mapServiceError(response.statusCode, code)
        }
        if (obj?.get("ok")?.jsonPrimitive?.booleanOrNull != true) {
            throw ManagedCookAIException.InvalidResponse()
        }
        return response.bodyText
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.yunle.fun/cook-ai-intent"
        private val ALLOWED_CAPABILITY_KEYS = setOf("ok", "serviceVersion", "enabled", "membership", "code")

        fun approvedEndpoint(rawUrl: String): String? {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "https" || (uri.port != -1 && uri.port != 443) ||
                uri.userInfo != null || uri.query != null || uri.fragment != null ||
                uri.host?.lowercase() != "api.yunle.fun" || uri.path != "/cook-ai-intent"
            ) {
                return null
            }
            return uri.toString()
        }

        private fun mapServiceError(status: Int, code: String?): ManagedCookAIException = when (status to code) {
            401 to "LOGIN_REQUIRED" -> ManagedCookAIException.LoginRequired()
            403 to "MEMBERSHIP_REQUIRED" -> ManagedCookAIException.MembershipRequired()
            403 to "ACCOUNT_UNAVAILABLE" -> ManagedCookAIException.AccountUnavailable()
            409 to "VERSION_MISMATCH" -> ManagedCookAIException.VersionMismatch()
            429 to "RATE_LIMITED" -> ManagedCookAIException.RateLimited()
            429 to "BUDGET_EXHAUSTED" -> ManagedCookAIException.BudgetExhausted()
            503 to "SERVICE_UNAVAILABLE" -> ManagedCookAIException.Unavailable()
            502 to "INVALID_MODEL_RESPONSE" -> ManagedCookAIException.InvalidResponse()
            504 to "MODEL_TIMEOUT" -> ManagedCookAIException.TimedOut()
            else -> if (status in 500..599 && code == null) {
                ManagedCookAIException.Unavailable()
            } else {
                ManagedCookAIException.InvalidResponse()
            }
        }
    }
}

/**
 * 会员托管版分步指导服务 (`https://api.yunle.fun/cook-ai-guidance`)。
 * 对齐 iOS `ManagedStepGuidanceService.swift`。
 */
class ManagedStepGuidanceService(
    rawEndpoint: String? = DEFAULT_ENDPOINT,
    private val transport: HttpTransport = IsolatedHttpPostTransport,
) {
    val endpoint: String? = rawEndpoint?.let(::approvedEndpoint)
    val isConfigured: Boolean get() = endpoint != null

    suspend fun complete(
        prompt: StepGuidancePrompt,
        credential: YunlefunCredential,
        assertCurrent: () -> Unit = {},
    ): StepGuidanceResponse {
        assertCurrent()
        checkAccess(credential)
        coroutineContext.ensureActive()
        assertCurrent()
        val fields = LinkedHashMap<String, JsonPrimitive>().apply {
            put("action", JsonPrimitive("guidance"))
            put("schemaVersion", JsonPrimitive(prompt.request.schemaVersion))
            put("policyVersion", JsonPrimitive(prompt.request.policyVersion))
            put("dataVersion", JsonPrimitive(prompt.request.dataVersion))
            put("sourceFingerprint", JsonPrimitive(prompt.request.sourceFingerprint))
            put("recipeID", JsonPrimitive(prompt.request.recipeID))
            put("stepID", JsonPrimitive(prompt.request.stepID))
            put("servings", JsonPrimitive(prompt.request.servings))
            put("kind", JsonPrimitive(prompt.request.kind.rawValue))
            put("requestID", JsonPrimitive(prompt.request.requestID))
            put("contextRevision", JsonPrimitive(prompt.request.contextRevision))
            prompt.request.question?.let { put("question", JsonPrimitive(it)) }
        }
        val obj = request(JsonObject(fields), credential)
        if (obj.keys != setOf("ok", "guidance")) throw StepGuidanceException.InvalidResponse()
        val guidanceObj = obj["guidance"] as? JsonObject ?: throw StepGuidanceException.InvalidResponse()
        if (guidanceObj.keys != EXPECTED_GUIDANCE_KEYS) throw StepGuidanceException.InvalidResponse()
        val decoded = runCatching {
            Json.decodeFromString<StepGuidanceResponse>(guidanceObj.toString())
        }.getOrElse { throw StepGuidanceException.InvalidResponse() }
        return StepGuidanceValidator.validate(decoded, prompt)
    }

    internal suspend fun checkAccess(credential: YunlefunCredential): Long {
        val obj = request(JsonObject(mapOf("action" to JsonPrimitive("capabilities"))), credential)
        val membership = obj["membership"] as? JsonObject ?: throw StepGuidanceException.InvalidResponse()
        if (obj.keys != setOf("ok", "serviceVersion", "enabled", "membership") ||
            obj["serviceVersion"] != JsonPrimitive(1) || membership.keys != setOf("isActive", "expireAt")
        ) throw StepGuidanceException.InvalidResponse()

        val enabled = (obj["enabled"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw StepGuidanceException.InvalidResponse()
        val active = (membership["isActive"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            ?: throw StepGuidanceException.InvalidResponse()
        val expireAt = when (val value = membership["expireAt"]) {
            JsonNull -> null
            is JsonPrimitive -> value.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 }
                ?: throw StepGuidanceException.InvalidResponse()
            else -> throw StepGuidanceException.InvalidResponse()
        }
        if (!enabled) {
            if (active) throw StepGuidanceException.NotDeployed()
            throw StepGuidanceException.MembershipRequired()
        }
        if (!active || expireAt == null || expireAt <= System.currentTimeMillis()) {
            throw StepGuidanceException.InvalidResponse()
        }
        return expireAt
    }

    private suspend fun request(body: JsonObject, credential: YunlefunCredential): JsonObject {
        val url = endpoint ?: throw StepGuidanceException.NotDeployed()
        if (!credential.isValid()) throw StepGuidanceException.LoginRequired()
        val response = try {
            transport.request(
                method = "POST",
                url = url,
                headers = mapOf("Authorization" to "Bearer ${credential.accessToken}"),
                jsonBody = body.toString(),
                maxResponseBytes = 65_536,
                readTimeoutMs = 35_000,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw StepGuidanceException.TimedOut()
        } catch (_: UnknownHostException) {
            throw StepGuidanceException.Offline()
        } catch (_: Exception) {
            throw StepGuidanceException.Unavailable()
        }

        val obj = runCatching { Json.parseToJsonElement(response.bodyText) as? JsonObject }.getOrNull()
        if (response.statusCode != 200) {
            val code = (obj?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content
            throw mapServiceError(response.statusCode, code)
        }
        if (obj == null || obj["ok"] != JsonPrimitive(true)) throw StepGuidanceException.InvalidResponse()
        return obj
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.yunle.fun/cook-ai-guidance"
        private val EXPECTED_GUIDANCE_KEYS = setOf(
            "schemaVersion", "policyVersion", "dataVersion", "sourceFingerprint",
            "recipeID", "stepID", "requestID", "contextRevision",
            "status", "answer", "sourceIDs",
        )

        fun approvedEndpoint(rawUrl: String): String? {
            val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "https" || (uri.port != -1 && uri.port != 443) ||
                uri.userInfo != null || uri.query != null || uri.fragment != null ||
                uri.host?.lowercase() != "api.yunle.fun" || uri.path != "/cook-ai-guidance"
            ) {
                return null
            }
            return uri.toString()
        }

        private fun mapServiceError(status: Int, code: String?): StepGuidanceException = when {
            status == 401 && code == "LOGIN_REQUIRED" -> StepGuidanceException.LoginRequired()
            status == 403 && code == "MEMBERSHIP_REQUIRED" -> StepGuidanceException.MembershipRequired()
            status == 403 && code == "ACCOUNT_UNAVAILABLE" -> StepGuidanceException.Unavailable()
            status == 404 || (status == 503 && code == "GUIDANCE_NOT_DEPLOYED") -> StepGuidanceException.NotDeployed()
            status == 409 && code in setOf("VERSION_MISMATCH", "SOURCE_MISMATCH", "SOURCE_NOT_FOUND") ->
                StepGuidanceException.SourceMismatch()
            status == 429 && code == "RATE_LIMITED" -> StepGuidanceException.RateLimited()
            status == 429 && code == "BUDGET_EXHAUSTED" -> StepGuidanceException.BudgetExhausted()
            status == 503 && code in setOf("SERVICE_UNAVAILABLE", "GUIDANCE_UNAVAILABLE") ->
                StepGuidanceException.Unavailable()
            status == 502 && code == "INVALID_MODEL_RESPONSE" -> StepGuidanceException.InvalidResponse()
            status == 504 && code == "MODEL_TIMEOUT" -> StepGuidanceException.TimedOut()
            status in 500..599 && code == null -> StepGuidanceException.Unavailable()
            else -> StepGuidanceException.InvalidResponse()
        }
    }
}

data class MembershipEntitlement(
    val userID: String,
    val accountToken: java.util.UUID,
    val isActive: Boolean,
    val expiresAtEpochMs: Long?,
) {
    fun isActiveAt(nowMs: Long = System.currentTimeMillis()): Boolean =
        isActive && expiresAtEpochMs?.let { it > nowMs } == true
}

enum class MembershipCheckReason { SCREEN, RESTORE, CHECKOUT_RETURN, PLAY_ORDER }

internal enum class MembershipCheckResult { UPDATED, RETRYABLE_FAILURE, NOT_UPDATED }

/**
 * 会员权益读取服务 (`account-api` -> `getAccount`)，对齐 iOS `MembershipService.swift`。
 */
class MembershipService(
    private val transport: HttpTransport = IsolatedHttpPostTransport,
    private val sandbox: Boolean = false,
) {
    suspend fun queryEntitlement(credential: YunlefunCredential): MembershipEntitlement {
        if (!credential.isValid()) {
            throw YunlefunAccountException.ExpiredSession()
        }
        val fnName = if (sandbox) "account-api-sandbox" else "account-api"
        val response = transport.request(
            method = "POST",
            url = "$FUNCTIONS_GATEWAY$fnName",
            headers = mapOf("Authorization" to "Bearer ${credential.accessToken}"),
            jsonBody = JsonObject(mapOf("action" to JsonPrimitive("getAccount"))).toString(),
            maxResponseBytes = 256 * 1024,
        )
        if (response.statusCode == 401) throw YunlefunAccountException.ExpiredSession()
        if (response.statusCode !in 200..299) {
            val status = response.statusCode
            throw YunlefunAccountException.Unavailable(retryable = status == 408 || status == 429 || status in 500..599)
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        if (body["code"] != null) {
            throw YunlefunAccountException.Unavailable(retryable = false)
        }
        return readMembershipEntitlement(credential.subject, body)
    }

    companion object {
        const val FUNCTIONS_GATEWAY = "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/v1/functions/"
    }
}

internal fun readMembershipEntitlement(subject: String, body: JsonObject): MembershipEntitlement {
    val membership = body["membership"] as? JsonObject
        ?: throw YunlefunAccountException.InvalidResponse()
    val isActive = (membership["isActive"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: throw YunlefunAccountException.InvalidResponse()
    val expiry = membership["expireAt"]
    val expireAtMs = if (expiry == null || expiry == JsonNull) null else {
        (expiry as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { ms ->
            ms.isFinite() && ms in 0.0..253_402_300_799_000.0
        }?.toLong() ?: throw YunlefunAccountException.InvalidResponse()
    }
    if (isActive && (expireAtMs == null || expireAtMs <= 0L)) {
        throw YunlefunAccountException.InvalidResponse()
    }
    return MembershipEntitlement(
        userID = subject,
        accountToken = MembershipAccountToken.forUser(subject),
        isActive = isActive,
        expiresAtEpochMs = if (isActive) expireAtMs else null,
    )
}

internal data class MembershipSession(val subject: String, val revision: Long)

internal sealed interface YunlefunSignInResult {
    data object SignedIn : YunlefunSignInResult
    data class ContinueInBrowser(val url: String) : YunlefunSignInResult
}

internal data class MembershipWebsiteReturn(val session: MembershipSession, val generation: Long)

internal data class UserLibraryScope(
    val session: MembershipSession? = null,
    val consentRevision: Long = 0,
    val usesAccountLibrary: Boolean = false,
    val isHydrated: Boolean = true,
)

private const val MEMBERSHIP_RETURN_PENDING_FIELD = "awaitingMembershipReturn"
private const val MEMBERSHIP_RETURN_BASELINE_FIELD = "membershipReturnBaselineExpiry"
private const val ACCOUNT_WEBSITE_PENDING_FIELD = "pendingAccountWebsite"

enum class DeviceLibraryCopyStatus { IDLE, COPYING, FAILED }

data class AccountSyncUiState(
    val credential: YunlefunCredential? = null,
    val account: YunlefunAccount? = null,
    val entitlement: MembershipEntitlement? = null,
    val isRefreshingProfile: Boolean = false,
    val profileRefreshError: String? = null,
    val syncEnabled: Boolean = false,
    val isSyncing: Boolean = false,
    val isHydrated: Boolean = true,
    val accountCacheUnreadable: Boolean = false,
    val lastSyncedAtMs: Long? = null,
    val accountDocument: UserDataDocument = UserDataDocument(),
    val statusMessage: String? = null,
    internal val appSignInNonce: String? = null,
    internal val isSavingSyncConsent: Boolean = false,
    internal val deviceLibraryCopyStatus: DeviceLibraryCopyStatus = DeviceLibraryCopyStatus.IDLE,
    val pendingWebsitePurpose: AccountWebsitePurpose? = null,
    internal val approvedWebsitePurpose: AccountWebsitePurpose? = null,
    internal val expiredWebsitePurpose: AccountWebsitePurpose? = null,
    val awaitingMembershipReturn: Boolean = false,
    val isCheckingMembership: Boolean = false,
    val membershipMessage: String? = null,
    internal val revision: Long = 0,
    internal val syncConsentRevision: Long = 0,
    internal val membershipReturnGeneration: Long = 0,
    internal val membershipReturnBaselineExpiry: Long = 0,
) {
    val usesAccountLibrary: Boolean
        get() = credential != null && syncEnabled

    val awaitingAccountWebsite: Boolean get() = pendingWebsitePurpose == AccountWebsitePurpose.ACCOUNT_SETTINGS
    internal val membershipSession: MembershipSession?
        get() = credential?.let { MembershipSession(it.subject, revision) }
    internal val membershipWebsiteReturn: MembershipWebsiteReturn?
        get() = membershipSession?.takeIf { awaitingMembershipReturn }
            ?.let { MembershipWebsiteReturn(it, membershipReturnGeneration) }
    internal val libraryScope: UserLibraryScope
        get() = UserLibraryScope(membershipSession, syncConsentRevision, usesAccountLibrary, isHydrated)
}

/**
 * 统一管理云乐坊账号会话、Room `AccountLibrary` 隔离缓存与显式跨平台同步。
 * 严格遵守：
 * 1. 首次登录绝不自动上传本机 `DeviceLibrary`；提供显式「将本机记录复制到当前账号」操作。
 * 2. 同步请求返回后与当前本地账号文档做二次合并 (`current.merging(accepted)`)。
 * 3. 退出登录或关闭同步时立即切回 `DeviceLibrary`，同时保留账号离线缓存。
 */
class YunlefunSessionManager(
    private val vault: SecretVault,
    private val database: CookDatabase,
    private val accountService: YunlefunAccountService = YunlefunAccountService(),
    private val syncService: UserDataSyncService = UserDataSyncService(),
    private val membershipService: MembershipService = MembershipService(),
    networkChanges: Flow<Unit> = emptyFlow(),
    private val accountWebsiteBootCount: Int? = null,
) {
    private val accountDao = database.accountLibraryDao()
    private val deviceDao = database.deviceLibraryDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionRevision = java.util.concurrent.atomic.AtomicLong(1L)
    private val hydratedSubjects = HashSet<String>()
    private val hydrationMutex = Mutex()
    private val mutationMutex = Mutex()
    private val syncMutex = Mutex()
    private val tokenRefreshMutex = Mutex()
    private val membershipCheckMutex = Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private data class SyncTarget(val session: MembershipSession, val consentRevision: Long)

    private val syncRequests = Channel<SyncTarget>(Channel.CONFLATED)
    // Guarded by syncMutex. A timed retry must belong to the exact attempt that failed.
    private var lastTransientSyncTarget: SyncTarget? = null
    private var syncChoiceRevision = 0L
    // Guarded by mutationMutex. Access can be restored while an older sync request is still in flight.
    private var activeMembershipCheckRevision = 0L
    private var syncWaitingForMembership: SyncTarget? = null
    private var activeSync: Job? = null
    @Volatile
    private var initialHydrationJob: Job? = null
    @Volatile
    internal var beforeAccountPersistCommitHook: (suspend () -> Unit)? = null

    @Serializable
    private data class PendingAccountWebsite(
        val handoff: YunlefunAccountWebHandoff,
        val purpose: AccountWebsitePurpose,
        val bootCount: Int?,
        val approvalBinding: String? = null,
        val approved: Boolean = false,
    ) {
        @kotlinx.serialization.Transient
        var approving = false
    }

    private var pendingAccountWebsite: PendingAccountWebsite? = null
    private var accountWebsiteExpiry: Job? = null
    private var appSignInExpiry: Job? = null
    private val _state = MutableStateFlow(AccountSyncUiState())
    val state: StateFlow<AccountSyncUiState> = _state.asStateFlow()

    init {
        restoreSavedSession()
        if (_state.value.credential == null) {
            runCatching { readPendingSso() }.getOrNull()
                ?.takeIf { it.inApp && it.isCurrentAt(System.currentTimeMillis()) }
                ?.let(::publishPendingAppSignIn)
        }
        scope.launch { runQueuedSync() }
        scope.launch {
            state.map { current -> current.libraryScope.takeIf { current.isHydrated && it.usesAccountLibrary } }
                .distinctUntilChanged()
                .collectLatest { library ->
                    if (library == null) return@collectLatest
                    networkChanges.collect {
                        mutationMutex.withLock {
                            if (_state.value.libraryScope == library) queueSyncLocked()
                        }
                    }
                }
        }
    }

    private fun restoreSavedSession() {
        val raw = runCatching { vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON) }.getOrNull()
            ?: return
        val session = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return
        val saved = runCatching { json.decodeFromJsonElement<YunlefunCredential>(session) }.getOrNull()
            ?: return
        val awaitingReturn = session[MEMBERSHIP_RETURN_PENDING_FIELD] == JsonPrimitive(true)
        val baselineExpiry = if (awaitingReturn) {
            (session[MEMBERSHIP_RETURN_BASELINE_FIELD] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0L) ?: 0L
        } else 0L
        val rev = sessionRevision.incrementAndGet()
        _state.value = _state.value.copy(
            credential = saved,
            isHydrated = false,
            awaitingMembershipReturn = awaitingReturn,
            membershipReturnBaselineExpiry = baselineExpiry,
            revision = rev,
        )
        if (accountWebsiteBootCount != null) {
            val pending = session[ACCOUNT_WEBSITE_PENDING_FIELD]?.let {
                runCatching { json.decodeFromJsonElement<PendingAccountWebsite>(it) }.getOrNull()
            }
            if (pending != null) {
                if (pending.bootCount == accountWebsiteBootCount && pending.handoff.isCurrentAt(SystemClock.elapsedRealtime())) {
                    publishPendingAccountWebsite(pending)
                } else {
                    _state.value = _state.value.copy(expiredWebsitePurpose = pending.purpose)
                }
            }
        }
        initialHydrationJob = scope.launch {
            runCatching { ensureHydrated(saved.subject, expectedRevision = rev) }
        }
    }

    private fun persistSession(
        credential: YunlefunCredential,
        awaitingMembershipReturn: Boolean = false,
        website: PendingAccountWebsite? = pendingAccountWebsite,
        baselineExpiry: Long = _state.value.membershipReturnBaselineExpiry,
    ) {
        val fields = json.encodeToJsonElement(YunlefunCredential.serializer(), credential).jsonObject.toMutableMap()
        if (awaitingMembershipReturn) {
            fields[MEMBERSHIP_RETURN_PENDING_FIELD] = JsonPrimitive(true)
            fields[MEMBERSHIP_RETURN_BASELINE_FIELD] = JsonPrimitive(baselineExpiry)
        }
        if (website?.bootCount != null) {
            fields[ACCOUNT_WEBSITE_PENDING_FIELD] = json.encodeToJsonElement(PendingAccountWebsite.serializer(), website)
        }
        vault.writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, JsonObject(fields).toString())
    }

    // Call under mutationMutex on the IO dispatcher. If storage fails, preserve in-process recovery.
    private fun setMembershipReturnPending(
        pending: Boolean,
        generation: Long = _state.value.membershipReturnGeneration,
        baselineExpiry: Long = 0,
    ) {
        val current = _state.value
        if (current.awaitingMembershipReturn != pending || current.membershipReturnBaselineExpiry != baselineExpiry) {
            runCatching { persistSession(requireNotNull(current.credential), pending, baselineExpiry = baselineExpiry) }
        }
        _state.value = current.copy(
            awaitingMembershipReturn = pending,
            membershipReturnGeneration = generation,
            membershipReturnBaselineExpiry = baselineExpiry,
        )
    }

    suspend fun awaitInitialHydration() {
        initialHydrationJob?.join()
    }

    @Synchronized
    private fun markHydrated(uid: String): Boolean = hydratedSubjects.add(uid)

    @Synchronized
    private fun isHydrated(uid: String): Boolean = uid in hydratedSubjects

    @Synchronized
    private fun clearHydrated() {
        hydratedSubjects.clear()
    }

    private suspend fun ensureHydrated(
        uid: String,
        expectedRevision: Long = sessionRevision.get(),
    ) = hydrationMutex.withLock {
        if (isHydrated(uid) && _state.value.credential?.subject == uid) {
            return@withLock
        }
        val syncState = accountDao.getSyncState(uid)
        val cachedDoc = decodeAccountCache(syncState)
        mutationMutex.withLock publish@{
            if (sessionRevision.get() != expectedRevision || _state.value.credential?.subject != uid) {
                return@publish
            }
            markHydrated(uid)
            _state.value = _state.value.copy(
                syncEnabled = cachedDoc != null && syncState?.syncEnabled == true,
                isHydrated = true,
                accountCacheUnreadable = cachedDoc == null,
                lastSyncedAtMs = syncState?.lastSyncedAtMs,
                accountDocument = cachedDoc ?: UserDataDocument(),
            )
        }
    }

    private fun decodeAccountCache(syncState: AccountSyncStateEntity?): UserDataDocument? {
        val raw = syncState?.lastSyncedDocumentJson?.takeIf { it.isNotBlank() } ?: return UserDataDocument()
        return try {
            UserDataDocument.decode(raw)
        } catch (_: UserDataDocument.InvalidDocumentException) {
            null
        }
    }

    private fun assertSessionUnchanged(expectedRevision: Long, expectedUid: String) {
        if (sessionRevision.get() != expectedRevision || _state.value.credential?.subject != expectedUid) {
            throw CancellationException("账号会话已变更，放弃过期的同步或状态写入")
        }
    }

    private suspend fun credentialForAccountRequest(
        revision: Long,
        subject: String,
        rejectedAccessToken: String? = null,
    ): YunlefunCredential =
        tokenRefreshMutex.withLock {
            val current = mutationMutex.withLock {
                assertSessionUnchanged(revision, subject)
                requireNotNull(_state.value.credential)
            }
            if (current.isValid() && current.accessToken != rejectedAccessToken) return@withLock current
            val fresh = accountService.refresh(current)
            if (!fresh.isValid() || fresh.subject != subject) throw YunlefunAccountException.ExpiredSession()
            mutationMutex.withLock {
                assertSessionUnchanged(revision, subject)
                persistSession(fresh, _state.value.awaitingMembershipReturn)
                _state.value = _state.value.copy(credential = fresh)
            }
            fresh
        }

    internal fun isCurrentMembershipSession(identity: MembershipSession): Boolean =
        sessionRevision.get() == identity.revision && state.value.membershipSession == identity

    internal suspend fun <T> withMembershipSession(
        identity: MembershipSession,
        request: suspend (YunlefunCredential) -> T,
    ): T = withMembershipSession(identity, rejectedAccessToken = null, request = request)

    internal suspend fun <T> withMembershipSession(
        identity: MembershipSession,
        rejectedAccessToken: String?,
        request: suspend (YunlefunCredential) -> T,
    ): T {
        val credential = credentialForAccountRequest(identity.revision, identity.subject, rejectedAccessToken)
        val response = request(credential)
        assertSessionUnchanged(identity.revision, identity.subject)
        return response
    }

    internal suspend fun <T> withMembershipSessionRetryingAuth(
        identity: MembershipSession,
        request: suspend (YunlefunCredential) -> T,
    ): T = withMembershipSession(identity) { credential ->
        try {
            request(credential)
        } catch (_: YunlefunAccountException.ExpiredSession) {
            withMembershipSession(identity, credential.accessToken, request)
        }
    }

    suspend fun beginSsoAuthorization(): String = withContext(Dispatchers.IO) {
        mutationMutex.withLock { newSsoLocked(inApp = false).authorizationUrl }
    }

    internal suspend fun beginAppSsoAuthorization(): YunlefunSSO = withContext(Dispatchers.IO) {
        mutationMutex.withLock { newSsoLocked(inApp = true) }
    }

    internal suspend fun continueAppSignInOnWeb(nonce: String): String = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            requireAppSignInLocked(nonce)
            newSsoLocked(inApp = false).authorizationUrl
        }
    }

    internal suspend fun cancelAppSignIn(nonce: String) = withContext(Dispatchers.IO) {
        mutationMutex.withLock {
            requireAppSignInLocked(nonce)
            clearSsoLocked()
        }
    }

    internal fun refreshAppSignInOnForeground() {
        val nonce = _state.value.appSignInNonce ?: return
        scope.launch {
            mutationMutex.withLock {
                if (_state.value.appSignInNonce != nonce) return@withLock
                val pending = runCatching { readPendingSso() }.getOrNull()
                if (pending?.nonce == nonce && !pending.isCurrentAt(System.currentTimeMillis())) {
                    expireAppSignInLocked()
                }
            }
        }
    }

    private fun newSsoLocked(inApp: Boolean): YunlefunSSO {
        if (_state.value.credential != null) throw YunlefunAccountException.AuthorizationFailed()
        val sso = YunlefunSSO(inApp = inApp)
        vault.writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_PENDING_SSO, json.encodeToString(sso))
        publishPendingAppSignIn(sso.takeIf { it.inApp })
        return sso
    }

    private fun readPendingSso(): YunlefunSSO? =
        vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_PENDING_SSO)?.let { raw ->
            runCatching { json.decodeFromString<YunlefunSSO>(raw) }.getOrNull()
        }

    private fun requireAppSignInLocked(nonce: String) {
        if (_state.value.appSignInNonce != nonce) throw YunlefunAccountException.InvalidCallback()
        val pending = readPendingSso()
        if (pending?.nonce != nonce || !pending.inApp) throw YunlefunAccountException.InvalidCallback()
        if (!pending.isCurrentAt(System.currentTimeMillis())) {
            expireAppSignInLocked()
            throw YunlefunAccountException.InvalidCallback()
        }
    }

    private fun clearSsoLocked() {
        vault.deleteSecret(AndroidKeystoreVault.KEY_YUNLEFUN_PENDING_SSO)
        publishPendingAppSignIn(null)
    }

    private fun expireAppSignInLocked() {
        runCatching { clearSsoLocked() }.onFailure { publishPendingAppSignIn(null) }
    }

    private fun publishPendingAppSignIn(sso: YunlefunSSO?) {
        appSignInExpiry?.cancel()
        _state.value = _state.value.copy(appSignInNonce = sso?.nonce)
        appSignInExpiry = sso?.let {
            scope.launch {
                delay((sso.createdAtMs + YunlefunSSO.LIFETIME_MS - System.currentTimeMillis()).coerceAtLeast(0))
                mutationMutex.withLock {
                    if (_state.value.appSignInNonce == sso.nonce) expireAppSignInLocked()
                }
            }
        }
    }

    internal suspend fun prepareAccountWebsite(
        purpose: AccountWebsitePurpose,
        expectedSession: MembershipSession? = null,
    ): AccountWebsiteLaunch = withContext(Dispatchers.IO) {
        val session = expectedSession ?: _state.value.membershipSession ?: throw YunlefunAccountException.ExpiredSession()
        assertSessionUnchanged(session.revision, session.subject)
        val supported = accountService.supportsAccountWebHandoff()
        assertSessionUnchanged(session.revision, session.subject)
        if (!supported && purpose == AccountWebsitePurpose.MEMBERSHIP_PURCHASE) throw YunlefunAccountException.CheckoutUnavailable()
        AccountWebsiteLaunch(session, purpose, supported)
    }

    internal suspend fun launchAccountWebsite(launch: AccountWebsiteLaunch, open: (String) -> Unit) {
        mutationMutex.withLock {
            assertSessionUnchanged(launch.session.revision, launch.session.subject)
            val previous = pendingAccountWebsite
            try {
                val pending = withContext(Dispatchers.IO) {
                    val next = if (launch.supportsHandoff) {
                        PendingAccountWebsite(YunlefunAccountWebHandoff(), launch.purpose, accountWebsiteBootCount)
                    } else null
                    savePendingAccountWebsite(next)
                    next
                }
                if (pending != null && !pending.handoff.isCurrentAt(SystemClock.elapsedRealtime())) {
                    throw YunlefunAccountException.InvalidWebsiteCallback()
                }
                open(pending?.handoff?.startUrl ?: YunlefunAccountWebHandoff.SETTINGS_URL)
            } catch (error: Exception) {
                if (pendingAccountWebsite !== previous) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val stillCurrent = previous?.takeIf { it.handoff.isCurrentAt(SystemClock.elapsedRealtime()) }
                        runCatching { savePendingAccountWebsite(stillCurrent) }.onFailure(error::addSuppressed)
                    }
                }
                throw error
            }
        }
    }

    suspend fun cancelAccountWebsite(purpose: AccountWebsitePurpose = AccountWebsitePurpose.ACCOUNT_SETTINGS): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                mutationMutex.withLock {
                    if (pendingAccountWebsite?.purpose == purpose) savePendingAccountWebsite(null)
                }
            }
        }

    internal fun refreshAccountWebsiteOnForeground() {
        if (state.value.pendingWebsitePurpose == null) return
        scope.launch {
            mutationMutex.withLock {
                val pending = pendingAccountWebsite ?: return@withLock
                // Elapsed time continues during sleep while a coroutine delay can remain queued.
                if (!pending.handoff.isCurrentAt(SystemClock.elapsedRealtime())) expireAccountWebsite(pending)
            }
        }
    }

    internal suspend fun approveAccountWebsiteCallback(
        callbackUrl: String,
        expectedPurpose: AccountWebsitePurpose? = null,
    ): ApprovedAccountWebsite = withContext(Dispatchers.IO) {
        val (session, pending, completion) = mutationMutex.withLock {
            val current = pendingAccountWebsite ?: throw YunlefunAccountException.InvalidWebsiteCallback()
            requireCurrentAccountWebsite(current)
            if (current.approving || (expectedPurpose != null && current.purpose != expectedPurpose)) {
                throw YunlefunAccountException.InvalidWebsiteCallback()
            }
            val session = _state.value.membershipSession ?: throw YunlefunAccountException.InvalidWebsiteCallback()
            val completion = current.handoff.complete(callbackUrl)
            val binding = completion.approval.getValue("binding")
            val bound = when (current.approvalBinding) {
                null -> current.copy(approvalBinding = binding).also { savePendingAccountWebsite(it) }
                binding -> current
                else -> throw YunlefunAccountException.InvalidWebsiteCallback()
            }
            bound.approving = !bound.approved
            Triple(session, bound, completion)
        }
        if (pending.approved) return@withContext approvedWebsite(pending, session, completion.url)
        val approved = try {
            var retriedConnection = false
            withMembershipSessionRetryingAuth(session) { credential ->
                suspend fun submit() {
                    val remainingMs = mutationMutex.withLock {
                        assertSessionUnchanged(session.revision, session.subject)
                        requireCurrentAccountWebsite(pending)
                        pending.handoff.expiresAtMs - SystemClock.elapsedRealtime()
                    }
                    withTimeoutOrNull(remainingMs) {
                        accountService.approveAccountWebHandoff(completion.approval, credential)
                    } ?: throw YunlefunAccountException.InvalidWebsiteCallback()
                }
                try {
                    submit()
                } catch (error: YunlefunAccountException.Unavailable) {
                    if (!error.retryable || retriedConnection) throw error
                    retriedConnection = true
                    delay(400)
                    submit()
                }
            }
            mutationMutex.withLock {
                assertSessionUnchanged(session.revision, session.subject)
                requireCurrentAccountWebsite(pending)
                pending.copy(approved = true).also { savePendingAccountWebsite(it) }
            }
        } finally {
            withContext(NonCancellable) {
                mutationMutex.withLock { pending.approving = false }
            }
        }
        approvedWebsite(approved, session, completion.url)
    }

    internal suspend fun resumeApprovedAccountWebsite(purpose: AccountWebsitePurpose): ApprovedAccountWebsite =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                val pending = pendingAccountWebsite?.takeIf { it.purpose == purpose && it.approved }
                    ?: throw YunlefunAccountException.InvalidWebsiteCallback()
                requireCurrentAccountWebsite(pending)
                val session = _state.value.membershipSession ?: throw YunlefunAccountException.InvalidWebsiteCallback()
                val binding = pending.approvalBinding ?: throw YunlefunAccountException.InvalidWebsiteCallback()
                approvedWebsite(pending, session, pending.handoff.continuationUrl(binding))
            }
        }

    private fun approvedWebsite(pending: PendingAccountWebsite, session: MembershipSession, url: String): ApprovedAccountWebsite {
        val destination = if (pending.purpose == AccountWebsitePurpose.MEMBERSHIP_PURCHASE) "$url#membership" else url
        return ApprovedAccountWebsite(destination, session, pending.handoff)
    }

    // Checked under mutationMutex before sending approval and before completing the handoff.
    private suspend fun requireCurrentAccountWebsite(pending: PendingAccountWebsite) {
        if (pendingAccountWebsite !== pending) throw YunlefunAccountException.InvalidWebsiteCallback()
        if (!pending.handoff.isCurrentAt(SystemClock.elapsedRealtime())) {
            withContext(Dispatchers.IO) { expireAccountWebsite(pending) }
            throw YunlefunAccountException.InvalidWebsiteCallback()
        }
    }

    internal suspend fun openApprovedAccountWebsite(website: ApprovedAccountWebsite, open: (String) -> Unit) {
        mutationMutex.withLock {
            assertSessionUnchanged(website.session.revision, website.session.subject)
            val pending = pendingAccountWebsite?.takeIf { it.approved && it.handoff === website.handoff }
                ?: throw YunlefunAccountException.InvalidWebsiteCallback()
            requireCurrentAccountWebsite(pending)
            val checkout = pending.purpose == AccountWebsitePurpose.MEMBERSHIP_PURCHASE
            val before = _state.value
            try {
                if (checkout) {
                    withContext(Dispatchers.IO) {
                        val baseline = before.entitlement?.takeIf { it.isActiveAt() }?.expiresAtEpochMs ?: 0L
                        setMembershipReturnPending(true, before.membershipReturnGeneration + 1, baseline)
                    }
                    requireCurrentAccountWebsite(pending)
                }
                open(website.url)
            } catch (error: Exception) {
                if (checkout) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        setMembershipReturnPending(
                            before.awaitingMembershipReturn,
                            before.membershipReturnGeneration,
                            before.membershipReturnBaselineExpiry,
                        )
                    }
                }
                throw error
            }
            publishPendingAccountWebsite(null)
            if (checkout) _state.value = _state.value.copy(membershipMessage = null)
            if (pending.bootCount != null) {
                withContext(NonCancellable + Dispatchers.IO) {
                    // The browser is already open. If saving fails, its grant can still only be redeemed once.
                    val delivered = _state.value
                    runCatching { persistSession(requireNotNull(delivered.credential), delivered.awaitingMembershipReturn) }
                }
            }
        }
    }

    // Run under mutationMutex on the IO dispatcher before opening or consuming a request.
    private fun savePendingAccountWebsite(pending: PendingAccountWebsite?, expiredPurpose: AccountWebsitePurpose? = null) {
        if (pendingAccountWebsite?.bootCount != null || pending?.bootCount != null) {
            val current = _state.value
            persistSession(requireNotNull(current.credential), current.awaitingMembershipReturn, website = pending)
        }
        publishPendingAccountWebsite(pending, expiredPurpose)
    }

    private fun expireAccountWebsite(pending: PendingAccountWebsite) {
        // An expired proof is also rejected on restore if the vault is unavailable.
        runCatching { savePendingAccountWebsite(null, expiredPurpose = pending.purpose) }
            .onFailure { publishPendingAccountWebsite(null, expiredPurpose = pending.purpose) }
    }

    private fun publishPendingAccountWebsite(pending: PendingAccountWebsite?, expiredPurpose: AccountWebsitePurpose? = null) {
        accountWebsiteExpiry?.cancel()
        pendingAccountWebsite = pending
        _state.value = _state.value.copy(
            pendingWebsitePurpose = pending?.purpose,
            approvedWebsitePurpose = pending?.takeIf { it.approved }?.purpose,
            expiredWebsitePurpose = expiredPurpose,
        )
        accountWebsiteExpiry = pending?.let {
            scope.launch {
                delay((pending.handoff.expiresAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                mutationMutex.withLock {
                    if (pendingAccountWebsite === pending) expireAccountWebsite(pending)
                }
            }
        }
    }

    internal suspend fun handleSsoCallbackUrl(callbackUrl: String): Result<YunlefunSignInResult> = withContext(Dispatchers.IO) {
        runCatching {
            val (callback, revision) = mutationMutex.withLock {
                if (_state.value.credential != null) throw YunlefunAccountException.InvalidCallback()
                val sso = readPendingSso()
                val now = System.currentTimeMillis()
                if (sso == null || !sso.isCurrentAt(now)) {
                    clearSsoLocked()
                    throw YunlefunAccountException.InvalidCallback()
                }
                val parsed = sso.callbackResult(callbackUrl, now)
                if (parsed == YunlefunSSO.Callback.SelectAccountOnWeb && sso.inApp) {
                    val url = newSsoLocked(inApp = false).authorizationUrl
                    return@runCatching YunlefunSignInResult.ContinueInBrowser(url)
                }
                clearSsoLocked()
                parsed to sessionRevision.get()
            }
            val credential = accountService.signIn(callback.exchangeFields(), deviceID = UUID.randomUUID().toString())
            establishSession(credential, revision)
            YunlefunSignInResult.SignedIn
        }
    }

    internal suspend fun signInWithWeChat(authorize: suspend () -> String?): Boolean =
        signInWithWeChat(authorize, signupProof = null, deviceID = UUID.randomUUID().toString())

    internal suspend fun registerWithWeChat(proof: String, deviceID: String, authorize: suspend () -> String?): Boolean =
        signInWithWeChat(authorize, signupProof = proof, deviceID = deviceID)

    private suspend fun signInWithWeChat(
        authorize: suspend () -> String?,
        signupProof: String?,
        deviceID: String,
    ): Boolean {
        val revision = mutationMutex.withLock {
            if (_state.value.credential != null) throw YunlefunAccountException.ExpiredSession()
            sessionRevision.get()
        }
        val code = authorize() ?: return false
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                if (sessionRevision.get() != revision) throw YunlefunAccountException.ExpiredSession()
            }
            val credential = if (signupProof == null) accountService.signInWithWeChat(code, deviceID)
            else accountService.registerWithWeChat(signupProof, code, deviceID)
            try {
                establishSession(credential, revision)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (signupProof != null) throw WeChatAccountException(WeChatAccountException.Reason.ACCOUNT_CREATED)
                throw error
            }
        }
        return true
    }

    internal suspend fun bindWeChat(authorize: suspend () -> String?): Boolean {
        val identity = mutationMutex.withLock {
            _state.value.membershipSession ?: throw YunlefunAccountException.ExpiredSession()
        }
        withContext(Dispatchers.IO) {
            credentialForAccountRequest(identity.revision, identity.subject)
        }
        assertSessionUnchanged(identity.revision, identity.subject)
        val code = authorize() ?: return false
        withContext(Dispatchers.IO) {
            withMembershipSession(identity) { credential ->
                assertSessionUnchanged(identity.revision, identity.subject)
                try {
                    accountService.bindWeChat(code, credential)
                } catch (rejected: YunlefunAccountException.ExpiredSession) {
                    val renewed = credentialForAccountRequest(
                        identity.revision,
                        identity.subject,
                        rejectedAccessToken = credential.accessToken,
                    )
                    if (renewed.accessToken == credential.accessToken) throw rejected
                    // The server may already have used the WeChat code before rejecting the session.
                    throw WeChatAccountException(WeChatAccountException.Reason.BINDING_REAUTHORIZE)
                }
            }
        }
        return true
    }

    private suspend fun establishSession(credential: YunlefunCredential, revision: Long): YunlefunAccount {
        try {
            val profile = accountService.account(credential)
            val entitlement = try {
                membershipService.queryEntitlement(credential)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            mutationMutex.withLock {
                if (sessionRevision.get() != revision) throw YunlefunAccountException.ExpiredSession()
                val syncState = accountDao.getSyncState(credential.subject)
                val cachedDoc = decodeAccountCache(syncState)
                coroutineContext.ensureActive()
                clearSsoLocked()
                persistSession(credential, website = null)
                val newRevision = sessionRevision.incrementAndGet()
                activeSync?.cancel()
                syncWaitingForMembership = null
                publishPendingAccountWebsite(null)
                clearHydrated()
                markHydrated(credential.subject)
                _state.value = AccountSyncUiState(
                    credential = credential,
                    account = profile,
                    entitlement = entitlement,
                    syncEnabled = cachedDoc != null && syncState?.syncEnabled == true,
                    isHydrated = true,
                    accountCacheUnreadable = cachedDoc == null,
                    lastSyncedAtMs = syncState?.lastSyncedAtMs,
                    accountDocument = cachedDoc ?: UserDataDocument(),
                    statusMessage = "已登录 ${profile.displayName}",
                    revision = newRevision,
                )
                queueSyncLocked()
            }
            return profile
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { accountService.signOut(credential) } }
            throw error
        }
    }

    fun requestForegroundProfileRefresh() {
        val current = state.value
        if (current.isRefreshingProfile) return
        val session = current.membershipSession ?: return
        scope.launch { refreshProfileAndEntitlement(session, onlyIfNeeded = true) }
    }

    internal suspend fun refreshProfileAndEntitlement(
        expectedSession: MembershipSession? = state.value.membershipSession,
        onlyIfNeeded: Boolean = false,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val session = expectedSession ?: throw YunlefunAccountException.ExpiredSession()
            membershipCheckMutex.withLock profileCheck@{
                val initial = mutationMutex.withLock start@{
                    assertSessionUnchanged(session.revision, session.subject)
                    val current = _state.value
                    val credential = requireNotNull(current.credential)
                    if (onlyIfNeeded && credential.isValid() && current.account != null &&
                        current.entitlement != null && current.profileRefreshError == null
                    ) return@start null
                    _state.value = current.copy(isRefreshingProfile = true, profileRefreshError = null)
                    current
                } ?: return@profileCheck
                val initialCredential = requireNotNull(initial.credential)
                var profileToken = initialCredential.accessToken
                try {
                    val profile = try {
                        withMembershipSessionRetryingAuth(session) { credential ->
                            profileToken = credential.accessToken
                            accountService.account(credential)
                        }
                    } catch (error: YunlefunAccountException.ExpiredSession) {
                        tokenRefreshMutex.withLock {
                            mutationMutex.withLock {
                                if (isCurrentMembershipSession(session) && _state.value.credential?.accessToken == profileToken) {
                                    try {
                                        vault.deleteSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON)
                                    } catch (_: Exception) {
                                        throw YunlefunAccountException.SecureStorage()
                                    }
                                    resetLocalSessionLocked("登录已过期，请重新登录。当前使用本机收藏与历史。")
                                }
                            }
                        }
                        throw error
                    }
                    // A checkout return has its own entitlement check and clears the persisted return marker.
                    val needsEntitlement = initial.entitlement == null || initial.profileRefreshError != null || !initialCredential.isValid()
                    val checkEntitlement = !onlyIfNeeded || (needsEntitlement && !_state.value.awaitingMembershipReturn)
                    val entitlement = if (checkEntitlement) {
                        try {
                            withMembershipSessionRetryingAuth(session, membershipService::queryEntitlement)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                    } else null
                    mutationMutex.withLock {
                        assertSessionUnchanged(session.revision, session.subject)
                        val current = _state.value
                        _state.value = current.copy(
                            account = profile,
                            entitlement = entitlement ?: current.entitlement,
                            profileRefreshError = "已刷新账号资料，暂时无法核对会员权益。".takeIf { checkEntitlement && entitlement == null },
                            statusMessage = if (!onlyIfNeeded && entitlement != null) "已刷新账号与会员状态" else current.statusMessage,
                        )
                        if (entitlement != null) handleMembershipSyncRecoveryLocked(entitlement)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    mutationMutex.withLock {
                        if (isCurrentMembershipSession(session)) {
                            val message = (error as? YunlefunAccountException)?.message
                            _state.value = _state.value.copy(profileRefreshError = message ?: "暂时无法刷新账号资料，请重试。")
                        }
                    }
                    throw error
                } finally {
                    withContext(NonCancellable) {
                        mutationMutex.withLock {
                            if (isCurrentMembershipSession(session)) {
                                _state.value = _state.value.copy(isRefreshingProfile = false)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun checkMembership(reason: MembershipCheckReason) {
        checkMembership(reason, expectedSession = null)
    }

    internal fun requestMembershipRestore(expected: MembershipSession) {
        scope.launch { checkMembership(MembershipCheckReason.RESTORE, expected) }
    }

    internal suspend fun recoverMembershipWebsiteReturn(
        returned: MembershipWebsiteReturn,
        checks: Flow<Unit>,
        retryDelaysMs: List<Long> = listOf(3_000L, 7_000L, 15_000L),
    ): Unit = coroutineScope {
        if (state.value.membershipWebsiteReturn != returned) return@coroutineScope
        val timedChecks = flow {
            for ((index, waitMs) in retryDelaysMs.withIndex()) {
                delay(waitMs)
                emit(index == retryDelaysMs.lastIndex)
            }
        }
        val retry = launch {
            merge(checks.map { false }, timedChecks).collect { finalCheck ->
                checkMembership(MembershipCheckReason.CHECKOUT_RETURN, returned.session, returned, finalCheck)
            }
        }
        try {
            state.first { it.membershipWebsiteReturn != returned }
        } finally {
            retry.cancel()
        }
    }

    internal suspend fun checkMembership(
        reason: MembershipCheckReason,
        expectedSession: MembershipSession?,
        expectedReturn: MembershipWebsiteReturn? = null,
        finalWebsiteCheck: Boolean = false,
    ): MembershipCheckResult = withContext(Dispatchers.IO) {
        val identity = mutationMutex.withLock {
            val subject = _state.value.credential?.subject ?: return@withLock null
            if (expectedSession != null && !isCurrentMembershipSession(expectedSession)) return@withLock null
            sessionRevision.get() to subject
        } ?: return@withContext MembershipCheckResult.NOT_UPDATED

        val (revision, subject) = identity
        membershipCheckMutex.withLock {
            val returnGeneration = mutationMutex.withLock claim@{
                assertSessionUnchanged(revision, subject)
                val current = _state.value
                if (reason == MembershipCheckReason.CHECKOUT_RETURN && !current.awaitingMembershipReturn) {
                    return@claim null
                }
                if (expectedReturn != null && current.membershipWebsiteReturn != expectedReturn) {
                    return@claim null
                }
                _state.value = current.copy(isCheckingMembership = true, membershipMessage = null)
                current.membershipReturnGeneration
            } ?: return@withContext MembershipCheckResult.NOT_UPDATED
            try {
                val entitlement = withMembershipSessionRetryingAuth(
                    MembershipSession(subject, revision), membershipService::queryEntitlement,
                )
                mutationMutex.withLock publish@{
                    assertSessionUnchanged(revision, subject)
                    if (expectedReturn != null && _state.value.membershipWebsiteReturn != expectedReturn) {
                        return@publish MembershipCheckResult.NOT_UPDATED
                    }
                    val current = _state.value
                    val checksWebsiteReturn = current.awaitingMembershipReturn &&
                        returnGeneration == current.membershipReturnGeneration &&
                        (reason == MembershipCheckReason.CHECKOUT_RETURN || reason == MembershipCheckReason.RESTORE)
                    val activeExpiry = entitlement.takeIf { it.isActiveAt() }?.expiresAtEpochMs ?: 0L
                    val returnStillPending = checksWebsiteReturn && activeExpiry <= current.membershipReturnBaselineExpiry
                    if (checksWebsiteReturn && (!returnStillPending || finalWebsiteCheck)) {
                        setMembershipReturnPending(false)
                    }
                    _state.value = _state.value.copy(
                        entitlement = entitlement,
                        membershipMessage = when {
                            reason == MembershipCheckReason.SCREEN -> null
                            returnStillPending -> "会员有效期尚未更新。若已支付，可稍后再核对。"
                            activeExpiry > 0L -> "当前账号的会员权益已核对。"
                            reason == MembershipCheckReason.PLAY_ORDER -> "当前账号尚无有效会员。"
                            else -> "当前账号尚无有效会员。若网页订单正在处理，可以稍后再核对。"
                        },
                    )
                    handleMembershipSyncRecoveryLocked(entitlement)
                    MembershipCheckResult.UPDATED
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutationMutex.withLock publish@{
                    assertSessionUnchanged(revision, subject)
                    if (expectedReturn != null && _state.value.membershipWebsiteReturn != expectedReturn) {
                        return@publish MembershipCheckResult.NOT_UPDATED
                    }
                    _state.value = _state.value.copy(
                        membershipMessage = if (error is YunlefunAccountException.ExpiredSession) error.message
                        else "暂时无法核对会员权益，请重试。",
                    )
                    if (error is IOException || (error as? YunlefunAccountException.Unavailable)?.retryable == true) {
                        MembershipCheckResult.RETRYABLE_FAILURE
                    } else {
                        MembershipCheckResult.NOT_UPDATED
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    mutationMutex.withLock {
                        if (sessionRevision.get() == revision && _state.value.credential?.subject == subject) {
                            _state.value = _state.value.copy(isCheckingMembership = false)
                        }
                    }
                }
            }
        }
    }

    internal fun requestSyncEnabled(enabled: Boolean, expected: UserLibraryScope) {
        scope.launch { setSyncEnabled(enabled, expected) }
    }

    internal suspend fun setSyncEnabled(enabled: Boolean, expected: UserLibraryScope? = null): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (session, choice) = mutationMutex.withLock {
                val current = _state.value
                if (expected != null && current.libraryScope != expected) throw UserDataSyncException.LibraryChanged()
                val session = current.membershipSession ?: throw UserDataSyncException.LoginRequired()
                _state.value = current.copy(isSavingSyncConsent = true)
                session to ++syncChoiceRevision
            }
            try {
                ensureHydrated(session.subject, session.revision)
                mutationMutex.withLock {
                    assertSessionUnchanged(session.revision, session.subject)
                    if (_state.value.accountCacheUnreadable) throw UserDataSyncException.AccountCacheUnreadable()
                }
                if (enabled) withSyncCredential(session, request = syncService::checkAccess)
                mutationMutex.withLock {
                    assertSessionUnchanged(session.revision, session.subject)
                    if (choice != syncChoiceRevision) throw CancellationException("同步设置已变更")
                    persistAccountDocument(session.subject, _state.value.accountDocument, syncEnabled = enabled)
                    activeSync?.cancel()
                    syncWaitingForMembership = null
                    _state.value = _state.value.copy(
                        syncEnabled = enabled,
                        isSyncing = false,
                        deviceLibraryCopyStatus = DeviceLibraryCopyStatus.IDLE,
                        statusMessage = if (enabled) "已开启账号跨端同步" else "已关闭跨端同步，已切回本机收藏与历史",
                        syncConsentRevision = _state.value.syncConsentRevision + 1,
                    )
                    if (enabled) queueSyncLocked()
                }
            } catch (failure: Exception) {
                if (failure !is CancellationException) mutationMutex.withLock {
                    if (_state.value.membershipSession == session && choice == syncChoiceRevision) {
                        _state.value = _state.value.copy(statusMessage = failure.message)
                    }
                }
                throw failure
            } finally {
                withContext(NonCancellable) {
                    mutationMutex.withLock {
                        val current = _state.value
                        if (current.membershipSession == session && choice == syncChoiceRevision) {
                            _state.value = current.copy(isSavingSyncConsent = false)
                        }
                    }
                }
            }
        }
    }

    internal fun requestDeviceLibraryCopy(expected: UserLibraryScope) {
        scope.launch {
            mutationMutex.withLock {
                val current = _state.value
                if (current.libraryScope != expected || !current.usesAccountLibrary ||
                    current.deviceLibraryCopyStatus == DeviceLibraryCopyStatus.COPYING
                ) return@launch
                _state.value = current.copy(deviceLibraryCopyStatus = DeviceLibraryCopyStatus.COPYING)
            }
            val failure = copyDeviceLibraryToAccount(expected).exceptionOrNull()
            mutationMutex.withLock {
                val current = _state.value
                if (current.libraryScope == expected) {
                    val status = if (failure == null || failure is CancellationException) {
                        DeviceLibraryCopyStatus.IDLE
                    } else {
                        DeviceLibraryCopyStatus.FAILED
                    }
                    _state.value = current.copy(deviceLibraryCopyStatus = status)
                }
            }
        }
    }

    /**
     * 显式将本机 `DeviceLibrary` 收藏与历史合并到当前账号库（不会在首次登录时自动静默上传）。
     */
    internal suspend fun copyDeviceLibraryToAccount(expected: UserLibraryScope): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val deviceDoc = database.withTransaction {
                val devFavs = deviceDao.getAllFavorites().map {
                    UserDataDocument.Favorite(id = it.recipeId, time = it.addedAtMs)
                }
                val devHistories = deviceDao.getAllHistories().mapNotNull { entity ->
                    runCatching {
                        UserDataDocument.Visit(
                            recipe = UserDataDocument.wireJson.decodeFromString<UserDataDocument.RecipeSnapshot>(entity.snapshotJson),
                            time = entity.visitedAtMs,
                        )
                    }.getOrNull()
                }
                UserDataDocument(
                    favorites = devFavs.take(UserDataDocument.MAX_FAVORITES),
                    histories = devHistories.take(UserDataDocument.MAX_HISTORIES),
                )
            }
            val merged = editAccountLibrary(expected, statusOnSave = {
                "已将本机记录合并到当前账号（共 ${it.favorites.size} 条收藏、${it.histories.size} 条历史）"
            }) { it.merging(deviceDoc) }
            Pair(merged.favorites.size, merged.histories.size)
        }
    }

    internal suspend fun toggleAccountFavorite(expected: UserLibraryScope, recipeId: Int, currentlyFavorite: Boolean): Boolean {
        editAccountLibrary(expected) { current ->
            val latest = (current.favorites + current.favoriteRemovals).asSequence()
                .filter { it.id == recipeId }.maxOfOrNull { it.time } ?: 0.0
            val nowMs = maxOf(System.currentTimeMillis().toDouble(), latest + 1)
            if (currentlyFavorite) {
                val nextFavs = current.favorites.filterNot { it.id == recipeId }
                val nextRemovals = (listOf(UserDataDocument.Favorite(id = recipeId, time = nowMs)) +
                    current.favoriteRemovals.filterNot { it.id == recipeId }).take(UserDataDocument.MAX_REMOVALS)
                current.copy(favorites = nextFavs, favoriteRemovals = nextRemovals)
            } else {
                val nextFavs = (listOf(UserDataDocument.Favorite(id = recipeId, time = nowMs)) +
                    current.favorites.filterNot { it.id == recipeId }).take(UserDataDocument.MAX_FAVORITES)
                val nextRemovals = current.favoriteRemovals.filterNot { it.id == recipeId }
                current.copy(favorites = nextFavs, favoriteRemovals = nextRemovals)
            }
        }
        return !currentlyFavorite
    }

    internal suspend fun recordAccountVisit(expected: UserLibraryScope, recipe: Recipe) {
        editAccountLibrary(expected) { current ->
            val latestVisit = current.histories.asSequence().filter { it.recipe.name == recipe.name }
                .maxOfOrNull { it.time } ?: 0.0
            val lastRemoval = current.historyRemovals.asSequence().filter { it.name == recipe.name }
                .maxOfOrNull { it.time } ?: 0.0
            val nowMs = maxOf(System.currentTimeMillis().toDouble(), latestVisit + 1, lastRemoval + 1, current.historyClearedAt + 1)
            val visit = UserDataDocument.Visit(
                recipe = UserDataDocument.RecipeSnapshot(recipe),
                time = nowMs,
            )
            val nextHistories = (listOf(visit) + current.histories)
                .distinctBy { it.recipe.name to it.time }
                .sortedByDescending { it.time }
                .take(UserDataDocument.MAX_HISTORIES)
            current.copy(histories = nextHistories)
        }
    }

    internal suspend fun removeAccountHistory(expected: UserLibraryScope, recipeName: String, visitedAtMs: Double) {
        editAccountLibrary(expected) { current ->
            val nextHistories = current.histories.filterNot { it.recipe.name == recipeName && it.time == visitedAtMs }
            val nextRemovals = (listOf(UserDataDocument.Removal(name = recipeName, time = visitedAtMs)) +
                current.historyRemovals.filterNot { it.name == recipeName && it.time == visitedAtMs })
                .take(UserDataDocument.MAX_REMOVALS)
            current.copy(histories = nextHistories, historyRemovals = nextRemovals)
        }
    }

    internal suspend fun importIntoAccount(expected: UserLibraryScope, incoming: UserDataDocument): Pair<Int, Int> {
        val merged = editAccountLibrary(expected) {
            it.importMerging(incoming, historyCapacity = UserDataDocument.MERGED_HISTORY_CAP)
        }
        return Pair(merged.favorites.size, merged.histories.size)
    }

    internal suspend fun clearAccountHistory(expected: UserLibraryScope) {
        editAccountLibrary(expected) { current ->
            current.clearingHistory(System.currentTimeMillis().toDouble())
        }
    }

    private suspend fun editAccountLibrary(
        expected: UserLibraryScope,
        statusOnSave: ((UserDataDocument) -> String)? = null,
        edit: (UserDataDocument) -> UserDataDocument,
    ): UserDataDocument = withContext(Dispatchers.IO) {
        val session = expected.session ?: throw UserDataSyncException.LoginRequired()
        mutationMutex.withLock {
            val current = _state.value
            if (current.libraryScope != expected || !current.usesAccountLibrary || !current.isHydrated) {
                throw UserDataSyncException.LibraryChanged()
            }
            val updated = edit(current.accountDocument)
            persistAccountDocument(session.subject, updated, syncEnabled = true)
            _state.value = current.copy(
                accountDocument = updated,
                statusMessage = statusOnSave?.invoke(updated) ?: current.statusMessage,
            )
            queueSyncLocked()
            updated
        }
    }

    suspend fun syncNow(): Result<UserDataDocument> = syncNow(expected = null)

    internal fun requestManualSync(expected: UserLibraryScope) {
        scope.launch { syncNow(expected) }
    }

    internal suspend fun syncNow(expected: UserLibraryScope?): Result<UserDataDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val target = mutationMutex.withLock {
                val current = _state.value
                if (expected != null && current.libraryScope != expected) throw UserDataSyncException.LibraryChanged()
                val session = current.membershipSession ?: throw UserDataSyncException.LoginRequired()
                if (current.accountCacheUnreadable) throw UserDataSyncException.AccountCacheUnreadable()
                if (!current.syncEnabled) throw UserDataSyncException.Disabled()
                SyncTarget(session, current.syncConsentRevision)
            }
            syncAndScheduleRetry(target)
        }
    }

    private suspend fun syncAndScheduleRetry(target: SyncTarget): UserDataDocument {
        val result = sync(target)
        if (isTransientSyncFailure(result.exceptionOrNull())) mutationMutex.withLock {
            if (matchesSyncLocked(target)) queueSyncLocked()
        }
        return result.getOrThrow()
    }

    fun requestForegroundSync() {
        scope.launch {
            awaitInitialHydration()
            mutationMutex.withLock { queueSyncLocked() }
        }
    }

    private fun queueSyncLocked() {
        val current = _state.value
        val session = current.membershipSession ?: return
        if (current.syncEnabled && current.isHydrated) {
            syncRequests.trySend(SyncTarget(session, current.syncConsentRevision))
        }
    }

    private fun matchesSyncLocked(target: SyncTarget): Boolean =
        _state.value.syncEnabled && _state.value.membershipSession == target.session &&
            _state.value.syncConsentRevision == target.consentRevision

    private fun handleMembershipSyncRecoveryLocked(entitlement: MembershipEntitlement) {
        if (!entitlement.isActiveAt()) return
        activeMembershipCheckRevision++
        val waiting = syncWaitingForMembership ?: return
        syncWaitingForMembership = null
        if (matchesSyncLocked(waiting)) queueSyncLocked()
    }

    private fun isTransientSyncFailure(error: Throwable?): Boolean =
        error is UserDataSyncException.Unavailable || error is YunlefunAccountException.Unavailable

    private suspend fun runQueuedSync() {
        for (first in syncRequests) {
            delay(750)
            var target = syncRequests.tryReceive().getOrNull() ?: first
            var retryDelay = 1_000L
            var retrying = false
            while (isTransientSyncFailure(sync(target, retrying = retrying).exceptionOrNull())) {
                val queued = withTimeoutOrNull(retryDelay) { syncRequests.receive() }
                retrying = queued == null
                if (queued == null) {
                    retryDelay = minOf(retryDelay * 2, 30_000L)
                } else {
                    target = queued
                    retryDelay = 1_000L
                }
            }
        }
    }

    private suspend fun syncCredential(
        session: MembershipSession,
        rejectedAccessToken: String? = null,
    ): YunlefunCredential = try {
        credentialForAccountRequest(session.revision, session.subject, rejectedAccessToken)
    } catch (_: YunlefunAccountException.ExpiredSession) {
        throw UserDataSyncException.LoginRequired()
    }

    private suspend fun <T> withSyncCredential(
        session: MembershipSession,
        confirmAccessBeforeRetry: Boolean = false,
        request: suspend (YunlefunCredential) -> T,
    ): T {
        val credential = syncCredential(session)
        return try {
            request(credential)
        } catch (failure: UserDataSyncException.LoginRequired) {
            if (!failure.canRefresh) throw failure
            coroutineContext.ensureActive()
            val renewed = syncCredential(session, rejectedAccessToken = credential.accessToken)
            if (renewed.deviceID != credential.deviceID || renewed.accessToken == credential.accessToken) throw failure
            coroutineContext.ensureActive()
            // An authentication error can also reflect an account deletion hold.
            // Check access without a document before repeating the write.
            val ready = if (confirmAccessBeforeRetry) confirmSyncAccess(session, renewed) else renewed
            request(ready)
        }
    }

    private suspend fun confirmSyncAccess(session: MembershipSession, renewed: YunlefunCredential): YunlefunCredential {
        var candidate = renewed
        repeat(2) {
            val rejection = try {
                syncService.checkAccess(candidate)
                null
            } catch (failure: UserDataSyncException.LoginRequired) {
                if (!failure.canRefresh) throw failure
                failure
            }
            val current = syncCredential(session)
            if (current.deviceID != renewed.deviceID) throw UserDataSyncException.LoginRequired()
            if (current.accessToken == candidate.accessToken) {
                if (rejection != null) throw rejection
                return current
            }
            candidate = current
        }
        throw UserDataSyncException.Unavailable()
    }

    private suspend fun sync(target: SyncTarget, retrying: Boolean = false): Result<UserDataDocument> = runCatching {
        syncMutex.withLock {
            coroutineScope {
                val job = coroutineContext[Job]
                val membershipCheckAtStart = mutationMutex.withLock {
                    if (!matchesSyncLocked(target)) throw CancellationException("账号或同步设置已变更")
                    if (retrying && lastTransientSyncTarget !== target) throw CancellationException("同步重试已失效")
                    activeSync = job
                    _state.value = _state.value.copy(isSyncing = true, statusMessage = null)
                    activeMembershipCheckRevision
                }
                try {
                    val accepted = withSyncCredential(target.session, confirmAccessBeforeRetry = true) { credential ->
                        val local = mutationMutex.withLock {
                            coroutineContext.ensureActive()
                            if (!matchesSyncLocked(target)) throw CancellationException("账号或同步设置已变更")
                            val current = _state.value
                            if (current.credential?.accessToken != credential.accessToken ||
                                current.credential.deviceID != credential.deviceID
                            ) {
                                throw UserDataSyncException.Unavailable()
                            }
                            current.accountDocument
                        }
                        syncService.sync(local, credential)
                    }
                    mutationMutex.withLock {
                        coroutineContext.ensureActive()
                        if (!matchesSyncLocked(target)) throw CancellationException("账号或同步设置已变更")
                        val merged = _state.value.accountDocument.merging(accepted)
                        val nowMs = System.currentTimeMillis()
                        persistAccountDocument(target.session.subject, merged, syncEnabled = true, lastSyncedAtMs = nowMs)
                        lastTransientSyncTarget = null
                        syncWaitingForMembership = null
                        _state.value = _state.value.copy(
                            lastSyncedAtMs = nowMs,
                            accountDocument = merged,
                            statusMessage = "同步成功（${merged.favorites.size} 条收藏 / ${merged.histories.size} 条历史）",
                        )
                        merged
                    }
                } catch (error: Exception) {
                    if (error !is CancellationException) mutationMutex.withLock {
                        if (matchesSyncLocked(target)) {
                            // A newer result supersedes any older timed retry for this account.
                            lastTransientSyncTarget = target.takeIf { isTransientSyncFailure(error) }
                            _state.value = _state.value.copy(statusMessage = error.message ?: "暂时无法同步，离线记录已保留。")
                            syncWaitingForMembership = target.takeIf { error is UserDataSyncException.MembershipRequired }
                            if (syncWaitingForMembership != null && activeMembershipCheckRevision != membershipCheckAtStart &&
                                _state.value.entitlement?.isActiveAt() == true
                            ) {
                                syncWaitingForMembership = null
                                queueSyncLocked()
                            }
                        }
                    }
                    throw error
                } finally {
                    withContext(NonCancellable) {
                        mutationMutex.withLock {
                            activeSync = null
                            if (matchesSyncLocked(target)) _state.value = _state.value.copy(isSyncing = false)
                        }
                    }
                }
            }
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val credToRevoke = mutationMutex.withLock {
            val currentCred = _state.value.credential
            // 在持锁状态下立即吊销本地凭据，避免延迟网络登出完成时误删后来登录的新会话
            resetLocalSessionLocked("已退出登录，当前使用本机收藏与历史")
            runCatching { vault.deleteSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON) }
            currentCred
        }
        if (credToRevoke != null) {
            runCatching { accountService.signOut(credToRevoke) }
        }
    }

    private fun resetLocalSessionLocked(message: String) {
        val newRevision = sessionRevision.incrementAndGet()
        activeSync?.cancel()
        syncWaitingForMembership = null
        publishPendingAccountWebsite(null)
        clearHydrated()
        _state.value = AccountSyncUiState(statusMessage = message, revision = newRevision)
        expireAppSignInLocked()
    }

    private suspend fun persistAccountDocument(
        uid: String,
        document: UserDataDocument,
        syncEnabled: Boolean,
        lastSyncedAtMs: Long? = _state.value.lastSyncedAtMs,
    ) {
        document.validate()
        val encoded = UserDataDocument.wireJson.encodeToString(document)
        val stateEntity = AccountSyncStateEntity(
            accountUid = uid,
            syncEnabled = syncEnabled,
            historyClearedAtMs = document.historyClearedAt,
            lastSyncedDocumentJson = encoded,
            updatedAtMs = System.currentTimeMillis().toDouble(),
            lastSyncedAtMs = lastSyncedAtMs,
        )
        val favoriteEntities = document.favorites.map { fav ->
            AccountFavoriteEntity(
                accountUid = uid,
                recipeId = fav.id,
                addedAtMs = fav.time,
            )
        }
        database.withTransaction {
            accountDao.persistAccountSnapshotTransactional(stateEntity, favoriteEntities)
            beforeAccountPersistCommitHook?.invoke()
        }
    }
}
