package cn.yunyoujun.cook.data.remote

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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * 与 @yunlefun/sso 的 SSO v3 协议一致；不接收 URL 中的会话或长期凭据。
 * 1:1 对齐 iOS `YunlefunSSO.swift`。
 */
class YunlefunSSO(
    val createdAtMs: Long = System.currentTimeMillis(),
    val nonce: String = randomToken(),
    val verifier: String = randomToken(),
) {
    sealed interface Callback {
        data class Authorized(val fields: Map<String, String>) : Callback
        data object Denied : Callback
        data object SelectAccountOnWeb : Callback
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

    fun callbackResult(callbackUrl: String, nowMs: Long = System.currentTimeMillis()): Callback {
        if (nowMs < createdAtMs || (nowMs - createdAtMs) >= LIFETIME_MS) {
            throw YunlefunAccountException.InvalidCallback()
        }
        if (!isCallback(callbackUrl)) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val fragment = runCatching { URI(callbackUrl).rawFragment }.getOrNull()
            ?: throw YunlefunAccountException.InvalidCallback()
        if (!fragment.startsWith("ylf_sso=") || fragment.length >= 4096) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val encoded = fragment.removePrefix("ylf_sso=")
        val decodedBytes = decodeBase64Url(encoded) ?: throw YunlefunAccountException.InvalidCallback()
        val payload = runCatching {
            Json.parseToJsonElement(decodedBytes.toString(Charsets.UTF_8)).jsonObject
        }.getOrElse { throw YunlefunAccountException.InvalidCallback() }

        if (!ALLOWED_FRAGMENT_KEYS.containsAll(payload.keys)) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val payloadNonce = payload["nonce"]?.jsonPrimitive?.takeIf { it.isString }?.content
        val payloadIss = payload["iss"]?.jsonPrimitive?.takeIf { it.isString }?.content
        val ok = payload["ok"]?.jsonPrimitive?.booleanOrNull
        if (payloadNonce != nonce || payloadIss != ISSUER || ok == null) {
            throw YunlefunAccountException.InvalidCallback()
        }
        if (!ok) {
            if (payload["code"] != null) {
                throw YunlefunAccountException.InvalidCallback()
            }
            return when (payload["reason"]?.jsonPrimitive?.takeIf { it.isString }?.content) {
                "access_denied" -> Callback.Denied
                "account_selection_required" -> Callback.SelectAccountOnWeb
                else -> throw YunlefunAccountException.AuthorizationFailed()
            }
        }
        if (payload["reason"] != null) {
            throw YunlefunAccountException.InvalidCallback()
        }
        val code = payload["code"]?.jsonPrimitive?.takeIf { it.isString }?.content
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
        when (val res = callbackResult(callbackUrl, nowMs)) {
            is Callback.Authorized -> res.fields
            Callback.Denied -> throw CancellationException("User cancelled SSO")
            Callback.SelectAccountOnWeb -> throw YunlefunAccountException.AuthorizationFailed()
        }

    companion object {
        const val ISSUER = "https://www.yunle.fun"
        const val CLIENT_ID = "cook-mobile"
        const val REDIRECT_URI = "https://cook.yunyoujun.cn/auth/callback?platform=native"
        const val SCOPE = "identity:bootstrap"
        const val LIFETIME_MS = 600_000L

        private val ALLOWED_FRAGMENT_KEYS = setOf("nonce", "iss", "ok", "code", "reason")
        private val CODE_REGEX = Regex("^[A-Za-z0-9_-]{43}$")
        private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+$")
        private val secureRandom = SecureRandom()

        fun isCallback(rawUrl: String): Boolean {
            val withoutFragment = rawUrl.substringBefore('#')
            return withoutFragment == REDIRECT_URI
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
    class Unavailable : YunlefunAccountException("暂时无法连接登录服务，请稍后重试。")
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
        return exchangeWeChatTicket(response, deviceID, subjectRequired = true)
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
        if (!otp.matches(Regex("[0-9]{6}")) || challenge.length !in 1..4096) {
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
        val response = weChatRequest("registerCookWeChat", mapOf("proof" to proof, "code" to code), deviceID)
        return try {
            exchangeWeChatTicket(response, deviceID, subjectRequired = false)
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
            weChat = true,
        )
        val response = runCatching { Json.parseToJsonElement(data).jsonObject }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        if (response["ok"] != JsonPrimitive(true)) throw YunlefunAccountException.InvalidResponse()
        return response
    }

    private suspend fun exchangeWeChatTicket(
        payload: JsonObject,
        deviceID: String,
        subjectRequired: Boolean,
    ): YunlefunCredential {
        if (payload.keys.any { it !in setOf("ok", "ticket", "subject") }) throw YunlefunAccountException.InvalidResponse()
        val subject = if (subjectRequired || "subject" in payload) payload.requiredWeChatString("subject", 512) else null
        val credential = exchangeCustomTicket(payload.requiredWeChatString("ticket", 32_768), deviceID)
        if (subject != null && credential.subject != subject) {
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
        weChat: Boolean = false,
    ): String {
        val headers = LinkedHashMap<String, String>()
        (deviceID ?: credential?.deviceID)?.let { headers["x-device-id"] = it }
        origin?.let { headers["Origin"] = it }
        credential?.let { headers["Authorization"] = "Bearer ${it.accessToken}" }

        val jsonBody = fields?.let { map ->
            JsonObject(map.mapValues { (_, v) -> JsonPrimitive(v) }).toString()
        }
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
        } catch (_: Exception) {
            throw YunlefunAccountException.Unavailable()
        }

        if (response.statusCode !in 200..299) {
            if (weChat) {
                val reason = runCatching {
                    Json.parseToJsonElement(response.bodyText).jsonObject.requiredWeChatString("reason", 128)
                }.getOrNull()
                if (reason == "not_authenticated") throw YunlefunAccountException.ExpiredSession()
                throw WeChatAccountException(
                    WeChatAccountException.Reason.entries.firstOrNull { it.wire == reason }
                        ?: WeChatAccountException.Reason.UNAVAILABLE,
                )
            }
            val errCode = runCatching {
                Json.parseToJsonElement(response.bodyText).jsonObject["error"]
                    ?.jsonPrimitive?.takeIf { it.isString }?.content
            }.getOrNull()
            if (response.statusCode == 401 || errCode in setOf(
                    "invalid_grant", "invalid_token", "unauthenticated", "user_not_found", "user_disabled",
                )
            ) {
                throw YunlefunAccountException.ExpiredSession()
            }
            throw YunlefunAccountException.Unavailable()
        }
        return response.bodyText
    }

    companion object {
        const val GATEWAY = "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/auth/v1/"
        const val TICKET_URL = "https://api.yunle.fun/sso-ticket"
        const val COOK_ORIGIN = "https://cook.yunyoujun.cn"
    }
}

sealed class UserDataSyncException(message: String) : Exception(message) {
    class LoginRequired : UserDataSyncException("请重新登录后同步，离线记录已保留。")
    class MembershipRequired : UserDataSyncException("跨平台同步需要有效的云乐坊会员，已有记录仍可在本机使用。")
    class Unavailable : UserDataSyncException("暂时无法同步，恢复联网后会重试。")
    class InvalidResponse : UserDataSyncException("同步服务或数据版本暂不兼容，已停止覆盖，请稍后重试。")
    class LocalStorage : UserDataSyncException("账号记录未能保存，原有数据已保留。请检查设备储存空间。")
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
        } catch (_: Exception) {
            throw UserDataSyncException.Unavailable()
        }

        if (response.statusCode in 300..399) {
            throw UserDataSyncException.InvalidResponse()
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }
            .getOrElse { throw UserDataSyncException.InvalidResponse() }
        val code = body["code"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty()
        if (response.statusCode == 401 || code in setOf("LOGIN_REQUIRED", "SESSION_CHANGED")) {
            throw UserDataSyncException.LoginRequired()
        }
        if (code == "MEMBERSHIP_REQUIRED") {
            throw UserDataSyncException.MembershipRequired()
        }
        if (code in setOf("INVALID_REMOTE", "INVALID_DOCUMENT", "DOCUMENT_LIMIT", "UNKNOWN_ACTION")) {
            throw UserDataSyncException.InvalidResponse()
        }
        if (response.statusCode !in 200..299 || body["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw UserDataSyncException.Unavailable()
        }
        if (body["protocolVersion"]?.jsonPrimitive?.intOrNull != UserDataDocument.PROTOCOL_VERSION) {
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

    suspend fun complete(prompt: StepGuidancePrompt, credential: YunlefunCredential): StepGuidanceResponse {
        val url = endpoint ?: throw StepGuidanceException.NotDeployed()
        if (!credential.isValid()) {
            throw StepGuidanceException.LoginRequired()
        }
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
        val response = try {
            transport.request(
                method = "POST",
                url = url,
                headers = mapOf("Authorization" to "Bearer ${credential.accessToken}"),
                jsonBody = JsonObject(fields).toString(),
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

        val obj = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }.getOrNull()
        if (response.statusCode != 200) {
            val code = obj?.get("code")?.jsonPrimitive?.takeIf { it.isString }?.content
            throw mapServiceError(response.statusCode, code)
        }
        if (obj == null || obj.keys != setOf("ok", "guidance") || obj["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw StepGuidanceException.InvalidResponse()
        }
        val guidanceObj = obj["guidance"]?.jsonObject ?: throw StepGuidanceException.InvalidResponse()
        if (guidanceObj.keys != EXPECTED_GUIDANCE_KEYS) {
            throw StepGuidanceException.InvalidResponse()
        }
        val decoded = runCatching {
            Json.decodeFromString<StepGuidanceResponse>(guidanceObj.toString())
        }.getOrElse { throw StepGuidanceException.InvalidResponse() }
        return StepGuidanceValidator.validate(decoded, prompt)
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

enum class MembershipCheckReason { SCREEN, RESTORE, CHECKOUT_RETURN }

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
        if (response.statusCode !in 200..299) {
            throw YunlefunAccountException.Unavailable()
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyText).jsonObject }
            .getOrElse { throw YunlefunAccountException.InvalidResponse() }
        if (body["code"] != null) {
            throw YunlefunAccountException.Unavailable()
        }
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
            userID = credential.subject,
            accountToken = MembershipAccountToken.forUser(credential.subject),
            isActive = isActive,
            expiresAtEpochMs = if (isActive) expireAtMs else null,
        )
    }

    companion object {
        const val FUNCTIONS_GATEWAY = "https://yunlefun-8g7ybcxc7345c490.api.tcloudbasegateway.com/v1/functions/"
    }
}

data class AccountSyncUiState(
    val credential: YunlefunCredential? = null,
    val account: YunlefunAccount? = null,
    val entitlement: MembershipEntitlement? = null,
    val syncEnabled: Boolean = false,
    val isSyncing: Boolean = false,
    val isHydrated: Boolean = true,
    val lastSyncedAtMs: Long? = null,
    val accountDocument: UserDataDocument = UserDataDocument(),
    val statusMessage: String? = null,
    val pendingWebsitePurpose: AccountWebsitePurpose? = null,
    val awaitingMembershipReturn: Boolean = false,
    val isCheckingMembership: Boolean = false,
    val membershipMessage: String? = null,
) {
    val usesAccountLibrary: Boolean
        get() = credential != null && syncEnabled

    val awaitingAccountWebsite: Boolean get() = pendingWebsitePurpose == AccountWebsitePurpose.ACCOUNT_SETTINGS
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
) {
    private val accountDao = database.accountLibraryDao()
    private val deviceDao = database.deviceLibraryDao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val sessionRevision = java.util.concurrent.atomic.AtomicLong(1L)
    private val hydratedSubjects = HashSet<String>()
    private val hydrationMutex = Mutex()
    private val mutationMutex = Mutex()
    private val tokenRefreshMutex = Mutex()
    private val membershipCheckMutex = Mutex()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var initialHydrationJob: Job? = null
    @Volatile
    internal var beforeAccountPersistCommitHook: (suspend () -> Unit)? = null

    private val pendingSso = AtomicReference<YunlefunSSO?>()
    private class PendingAccountWebsite(
        val handoff: YunlefunAccountWebHandoff,
        val subject: String,
        val revision: Long,
        val purpose: AccountWebsitePurpose,
    )

    private var pendingAccountWebsite: PendingAccountWebsite? = null
    private val _state = MutableStateFlow(AccountSyncUiState())
    val state: StateFlow<AccountSyncUiState> = _state.asStateFlow()

    val isAccountLibraryActive: Boolean
        get() {
            val s = _state.value
            return s.credential != null && s.syncEnabled
        }

    init {
        restoreSavedSession()
    }

    private fun restoreSavedSession() {
        val raw = runCatching { vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON) }.getOrNull()
            ?: return
        val saved = runCatching { json.decodeFromString<YunlefunCredential>(raw) }.getOrNull()
            ?: return
        val rev = sessionRevision.incrementAndGet()
        _state.value = _state.value.copy(credential = saved, isHydrated = false)
        initialHydrationJob = scope.launch {
            runCatching { ensureHydrated(saved.subject, expectedRevision = rev) }
        }
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
    ): UserDataDocument = hydrationMutex.withLock {
        if (isHydrated(uid) && _state.value.credential?.subject == uid) {
            return _state.value.accountDocument
        }
        val syncState = accountDao.getSyncState(uid)
        val cachedDoc = syncState?.lastSyncedDocumentJson?.takeIf { it.isNotBlank() }?.let {
            runCatching { UserDataDocument.decode(it) }.getOrNull()
        } ?: UserDataDocument()
        if (sessionRevision.get() == expectedRevision && _state.value.credential?.subject == uid) {
            val mergedDoc = cachedDoc.merging(_state.value.accountDocument)
            markHydrated(uid)
            _state.value = _state.value.copy(
                syncEnabled = syncState?.syncEnabled == true || _state.value.syncEnabled,
                isHydrated = true,
                lastSyncedAtMs = syncState?.updatedAtMs?.toLong() ?: _state.value.lastSyncedAtMs,
                accountDocument = mergedDoc,
            )
            return mergedDoc
        }
        return cachedDoc
    }

    private fun assertSessionUnchanged(expectedRevision: Long, expectedUid: String) {
        if (sessionRevision.get() != expectedRevision || _state.value.credential?.subject != expectedUid) {
            throw CancellationException("账号会话已变更，放弃过期的同步或状态写入")
        }
    }

    private suspend fun credentialForAccountRequest(revision: Long, subject: String): YunlefunCredential =
        tokenRefreshMutex.withLock {
            val current = mutationMutex.withLock {
                assertSessionUnchanged(revision, subject)
                requireNotNull(_state.value.credential)
            }
            if (current.isValid()) return@withLock current
            val fresh = accountService.refresh(current)
            if (!fresh.isValid()) throw YunlefunAccountException.ExpiredSession()
            mutationMutex.withLock {
                assertSessionUnchanged(revision, subject)
                vault.writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, json.encodeToString(fresh))
                _state.value = _state.value.copy(credential = fresh)
            }
            fresh
        }

    fun beginSsoAuthorization(): String {
        val sso = YunlefunSSO()
        pendingSso.set(sso)
        return sso.authorizationUrl
    }

    suspend fun beginAccountWebsite(purpose: AccountWebsitePurpose = AccountWebsitePurpose.ACCOUNT_SETTINGS): String {
        val (revision, subject) = mutationMutex.withLock {
            val credential = _state.value.credential ?: throw YunlefunAccountException.ExpiredSession()
            sessionRevision.get() to credential.subject
        }
        val supported = accountService.supportsAccountWebHandoff()
        return mutationMutex.withLock {
            assertSessionUnchanged(revision, subject)
            clearPendingAccountWebsite()
            if (!supported) {
                if (purpose == AccountWebsitePurpose.MEMBERSHIP_PURCHASE) throw YunlefunAccountException.CheckoutUnavailable()
                return@withLock YunlefunAccountWebHandoff.SETTINGS_URL
            }

            val pending = PendingAccountWebsite(YunlefunAccountWebHandoff(), subject, revision, purpose)
            pendingAccountWebsite = pending
            _state.value = _state.value.copy(pendingWebsitePurpose = purpose)
            scope.launch {
                delay(YunlefunAccountWebHandoff.LIFETIME_MS)
                mutationMutex.withLock {
                    if (pendingAccountWebsite === pending) clearPendingAccountWebsite()
                }
            }
            pending.handoff.startUrl
        }
    }

    suspend fun cancelAccountWebsite(purpose: AccountWebsitePurpose = AccountWebsitePurpose.ACCOUNT_SETTINGS): Unit =
        mutationMutex.withLock {
            if (pendingAccountWebsite?.purpose == purpose) clearPendingAccountWebsite()
        }

    suspend fun openAccountWebsiteFromCallback(
        callbackUrl: String,
        expectedPurpose: AccountWebsitePurpose? = null,
        open: (String) -> Unit,
    ) {
        val (pending, completion) = mutationMutex.withLock {
            val current = pendingAccountWebsite ?: throw YunlefunAccountException.InvalidWebsiteCallback()
            if (expectedPurpose != null && current.purpose != expectedPurpose) {
                throw YunlefunAccountException.InvalidWebsiteCallback()
            }
            assertSessionUnchanged(current.revision, current.subject)
            val completion = current.handoff.complete(callbackUrl)
            clearPendingAccountWebsite()
            current to completion
        }
        val credential = credentialForAccountRequest(pending.revision, pending.subject)
        accountService.approveAccountWebHandoff(completion.approval, credential)
        mutationMutex.withLock {
            assertSessionUnchanged(pending.revision, pending.subject)
            open(completion.url)
            if (pending.purpose == AccountWebsitePurpose.MEMBERSHIP_PURCHASE) {
                _state.value = _state.value.copy(awaitingMembershipReturn = true, membershipMessage = null)
            }
        }
    }

    private fun clearPendingAccountWebsite() {
        pendingAccountWebsite = null
        _state.value = _state.value.copy(pendingWebsitePurpose = null)
    }

    suspend fun handleSsoCallbackUrl(callbackUrl: String): Result<YunlefunAccount> = withContext(Dispatchers.IO) {
        runCatching {
            val (sso, revision) = mutationMutex.withLock {
                (pendingSso.getAndSet(null) ?: throw YunlefunAccountException.InvalidCallback()) to sessionRevision.get()
            }
            val fields = sso.exchangeFields(callbackUrl)
            val credential = accountService.signIn(fields, deviceID = UUID.randomUUID().toString())
            establishSession(credential, revision)
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
        val (subject, revision) = mutationMutex.withLock {
            val credential = _state.value.credential ?: throw YunlefunAccountException.ExpiredSession()
            if (!credential.isValid()) throw YunlefunAccountException.ExpiredSession()
            credential.subject to sessionRevision.get()
        }
        val code = authorize() ?: return false
        withContext(Dispatchers.IO) {
            val credential = mutationMutex.withLock {
                assertSessionUnchanged(revision, subject)
                _state.value.credential ?: throw YunlefunAccountException.ExpiredSession()
            }
            accountService.bindWeChat(code, credential)
            assertSessionUnchanged(revision, subject)
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
                val cachedDoc = syncState?.lastSyncedDocumentJson?.takeIf { it.isNotBlank() }?.let {
                    runCatching { UserDataDocument.decode(it) }.getOrNull()
                } ?: UserDataDocument()
                coroutineContext.ensureActive()
                vault.writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, json.encodeToString(credential))
                sessionRevision.incrementAndGet()
                pendingSso.set(null)
                pendingAccountWebsite = null
                clearHydrated()
                markHydrated(credential.subject)
                _state.value = AccountSyncUiState(
                    credential = credential,
                    account = profile,
                    entitlement = entitlement,
                    syncEnabled = syncState?.syncEnabled == true,
                    isHydrated = true,
                    lastSyncedAtMs = syncState?.updatedAtMs?.toLong(),
                    accountDocument = cachedDoc,
                    statusMessage = "已登录 ${profile.displayName}",
                )
            }
            return profile
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { accountService.signOut(credential) } }
            throw error
        }
    }

    suspend fun refreshProfileAndEntitlement(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val (revision, subject) = mutationMutex.withLock {
                val credential = _state.value.credential ?: throw YunlefunAccountException.ExpiredSession()
                sessionRevision.get() to credential.subject
            }
            membershipCheckMutex.withLock {
                ensureHydrated(subject, revision)
                val credential = credentialForAccountRequest(revision, subject)
                val profile = accountService.account(credential)
                val entitlement = try {
                    membershipService.queryEntitlement(credential)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                mutationMutex.withLock {
                    assertSessionUnchanged(revision, subject)
                    val syncState = accountDao.getSyncState(subject)
                    val cachedDoc = syncState?.lastSyncedDocumentJson?.takeIf { it.isNotBlank() }?.let {
                        runCatching { UserDataDocument.decode(it) }.getOrNull()
                    }?.merging(_state.value.accountDocument) ?: _state.value.accountDocument
                    _state.value = _state.value.copy(
                        account = profile,
                        entitlement = entitlement ?: _state.value.entitlement,
                        syncEnabled = syncState?.syncEnabled == true || _state.value.syncEnabled,
                        accountDocument = cachedDoc,
                        statusMessage = if (entitlement == null) "已刷新账号资料，暂时无法核对会员权益。" else "已刷新账号与会员状态",
                    )
                }
            }
        }
    }

    suspend fun checkMembership(reason: MembershipCheckReason) = withContext(Dispatchers.IO) {
        val identity = mutationMutex.withLock {
            val subject = _state.value.credential?.subject ?: return@withLock null
            if (reason == MembershipCheckReason.CHECKOUT_RETURN) {
                if (!_state.value.awaitingMembershipReturn) return@withLock null
                _state.value = _state.value.copy(awaitingMembershipReturn = false)
            }
            sessionRevision.get() to subject
        } ?: return@withContext

        val (revision, subject) = identity
        membershipCheckMutex.withLock {
            mutationMutex.withLock {
                assertSessionUnchanged(revision, subject)
                _state.value = _state.value.copy(isCheckingMembership = true, membershipMessage = null)
            }
            try {
                val credential = credentialForAccountRequest(revision, subject)
                val entitlement = membershipService.queryEntitlement(credential)
                mutationMutex.withLock {
                    assertSessionUnchanged(revision, subject)
                    _state.value = _state.value.copy(
                        entitlement = entitlement,
                        membershipMessage = when {
                            reason == MembershipCheckReason.SCREEN -> null
                            entitlement.isActiveAt() -> "当前账号的会员权益已核对。"
                            else -> "当前账号尚无有效会员。若网页订单正在处理，可以稍后再核对。"
                        },
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutationMutex.withLock {
                    assertSessionUnchanged(revision, subject)
                    _state.value = _state.value.copy(
                        membershipMessage = if (error is YunlefunAccountException.ExpiredSession) error.message
                        else "暂时无法核对会员权益，请重试。",
                    )
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

    suspend fun setSyncEnabled(enabled: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cred = _state.value.credential ?: throw UserDataSyncException.LoginRequired()
            val capturedRev = sessionRevision.get()
            val capturedUid = cred.subject
            val hydratedDoc = ensureHydrated(capturedUid, capturedRev)
            if (enabled) {
                syncService.checkAccess(cred)
            }
            mutationMutex.withLock {
                assertSessionUnchanged(capturedRev, capturedUid)
                val currentDoc = hydratedDoc.merging(_state.value.accountDocument)
                persistAccountDocument(capturedUid, currentDoc, syncEnabled = enabled)
                _state.value = _state.value.copy(
                    syncEnabled = enabled,
                    accountDocument = currentDoc,
                    statusMessage = if (enabled) "已开启账号跨端同步" else "已关闭跨端同步，已切回本机收藏与历史",
                )
            }
            if (enabled) {
                syncNow().getOrThrow()
            }
        }
    }

    /**
     * 显式将本机 `DeviceLibrary` 收藏与历史合并到当前账号库（不会在首次登录时自动静默上传）。
     */
    suspend fun copyDeviceLibraryToAccount(): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching {
            val cred = _state.value.credential ?: throw UserDataSyncException.LoginRequired()
            val capturedRev = sessionRevision.get()
            val capturedUid = cred.subject
            val baseDoc = ensureHydrated(capturedUid, capturedRev)
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
            val deviceDoc = UserDataDocument(
                favorites = devFavs.take(UserDataDocument.MAX_FAVORITES),
                histories = devHistories.take(UserDataDocument.MAX_HISTORIES),
            )
            val merged = mutationMutex.withLock {
                assertSessionUnchanged(capturedRev, capturedUid)
                val combined = baseDoc.merging(_state.value.accountDocument).merging(deviceDoc)
                persistAccountDocument(capturedUid, combined, syncEnabled = _state.value.syncEnabled)
                _state.value = _state.value.copy(
                    accountDocument = combined,
                    statusMessage = "已将本机记录合并到当前账号（共 ${combined.favorites.size} 条收藏、${combined.histories.size} 条历史）",
                )
                combined
            }
            if (_state.value.syncEnabled) {
                runCatching { syncNow().getOrThrow() }
            }
            Pair(merged.favorites.size, merged.histories.size)
        }
    }

    suspend fun toggleAccountFavorite(recipeId: Int, currentlyFavorite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val cred = _state.value.credential ?: throw UserDataSyncException.LoginRequired()
        val capturedRev = sessionRevision.get()
        val capturedUid = cred.subject
        val hydrated = ensureHydrated(capturedUid, capturedRev)
        val shouldSync = mutationMutex.withLock {
            assertSessionUnchanged(capturedRev, capturedUid)
            val current = hydrated.merging(_state.value.accountDocument)
            val nowMs = System.currentTimeMillis().toDouble()
            val updated = if (currentlyFavorite) {
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
            persistAccountDocument(capturedUid, updated, syncEnabled = _state.value.syncEnabled)
            _state.value = _state.value.copy(accountDocument = updated)
            _state.value.syncEnabled
        }
        if (shouldSync) {
            scope.launch { runCatching { syncNow() } }
        }
        !currentlyFavorite
    }

    suspend fun recordAccountVisit(recipe: Recipe) = withContext(Dispatchers.IO) {
        val cred = _state.value.credential ?: return@withContext
        val capturedRev = sessionRevision.get()
        val capturedUid = cred.subject
        val hydrated = ensureHydrated(capturedUid, capturedRev)
        mutationMutex.withLock {
            assertSessionUnchanged(capturedRev, capturedUid)
            val current = hydrated.merging(_state.value.accountDocument)
            val nowMs = System.currentTimeMillis().toDouble()
            val visit = UserDataDocument.Visit(
                recipe = UserDataDocument.RecipeSnapshot(recipe),
                time = nowMs,
            )
            val nextHistories = (listOf(visit) + current.histories)
                .distinctBy { it.recipe.name to it.time }
                .sortedByDescending { it.time }
                .take(UserDataDocument.MAX_HISTORIES)
            val updated = current.copy(histories = nextHistories)
            persistAccountDocument(capturedUid, updated, syncEnabled = _state.value.syncEnabled)
            _state.value = _state.value.copy(accountDocument = updated)
        }
    }

    suspend fun removeAccountHistory(recipeName: String, visitedAtMs: Double) = withContext(Dispatchers.IO) {
        val cred = _state.value.credential ?: return@withContext
        val capturedRev = sessionRevision.get()
        val capturedUid = cred.subject
        val hydrated = ensureHydrated(capturedUid, capturedRev)
        mutationMutex.withLock {
            assertSessionUnchanged(capturedRev, capturedUid)
            val current = hydrated.merging(_state.value.accountDocument)
            val nextHistories = current.histories.filterNot { it.recipe.name == recipeName && it.time == visitedAtMs }
            val nextRemovals = (listOf(UserDataDocument.Removal(name = recipeName, time = visitedAtMs)) +
                current.historyRemovals.filterNot { it.name == recipeName && it.time == visitedAtMs })
                .take(UserDataDocument.MAX_REMOVALS)
            val updated = current.copy(histories = nextHistories, historyRemovals = nextRemovals)
            persistAccountDocument(capturedUid, updated, syncEnabled = _state.value.syncEnabled)
            _state.value = _state.value.copy(accountDocument = updated)
        }
    }

    suspend fun importIntoAccount(incoming: UserDataDocument): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val cred = _state.value.credential ?: throw UserDataSyncException.LoginRequired()
        val capturedRev = sessionRevision.get()
        val capturedUid = cred.subject
        val hydrated = ensureHydrated(capturedUid, capturedRev)
        val merged = mutationMutex.withLock {
            assertSessionUnchanged(capturedRev, capturedUid)
            val base = hydrated.importMerging(_state.value.accountDocument)
            val combined = base.importMerging(incoming)
            persistAccountDocument(capturedUid, combined, syncEnabled = _state.value.syncEnabled)
            _state.value = _state.value.copy(accountDocument = combined)
            combined
        }
        if (_state.value.syncEnabled) {
            scope.launch { runCatching { syncNow() } }
        }
        Pair(merged.favorites.size, merged.histories.size)
    }

    suspend fun clearAccountHistory() = withContext(Dispatchers.IO) {
        val cred = _state.value.credential ?: return@withContext
        val capturedRev = sessionRevision.get()
        val capturedUid = cred.subject
        val hydrated = ensureHydrated(capturedUid, capturedRev)
        mutationMutex.withLock {
            assertSessionUnchanged(capturedRev, capturedUid)
            val current = hydrated.merging(_state.value.accountDocument)
            val nowMs = System.currentTimeMillis().toDouble()
            val updated = current.copy(
                histories = emptyList(),
                historyRemovals = emptyList(),
                historyClearedAt = maxOf(current.historyClearedAt, nowMs),
            )
            persistAccountDocument(capturedUid, updated, syncEnabled = _state.value.syncEnabled)
            _state.value = _state.value.copy(accountDocument = updated)
        }
    }

    suspend fun syncNow(): Result<UserDataDocument> = withContext(Dispatchers.IO) {
        runCatching {
            val cred = _state.value.credential ?: throw UserDataSyncException.LoginRequired()
            val capturedRev = sessionRevision.get()
            val capturedUid = cred.subject
            val localBefore = ensureHydrated(capturedUid, capturedRev)
            _state.value = _state.value.copy(isSyncing = true, statusMessage = null)
            try {
                val accepted = syncService.sync(localBefore, cred)
                mutationMutex.withLock {
                    // 在锁内校验会话修订号与账号主体，确保 persist 与状态发布不会被并发 signOut/换号穿透
                    assertSessionUnchanged(capturedRev, capturedUid)
                    val mergedAfter = _state.value.accountDocument.merging(accepted)
                    val nowMs = System.currentTimeMillis()
                    persistAccountDocument(capturedUid, mergedAfter, syncEnabled = true, nowMs = nowMs)
                    assertSessionUnchanged(capturedRev, capturedUid)
                    _state.value = _state.value.copy(
                        isSyncing = false,
                        lastSyncedAtMs = nowMs,
                        accountDocument = mergedAfter,
                        statusMessage = "同步成功（${mergedAfter.favorites.size} 条收藏 / ${mergedAfter.histories.size} 条历史）",
                    )
                    mergedAfter
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutationMutex.withLock {
                    if (sessionRevision.get() == capturedRev && _state.value.credential?.subject == capturedUid) {
                        _state.value = _state.value.copy(
                            isSyncing = false,
                            statusMessage = e.message ?: "暂时无法同步，离线记录已保留。",
                        )
                    }
                }
                throw e
            }
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        val credToRevoke = mutationMutex.withLock {
            val currentCred = _state.value.credential
            sessionRevision.incrementAndGet()
            clearPendingAccountWebsite()
            clearHydrated()
            _state.value = AccountSyncUiState(statusMessage = "已退出登录，当前使用本机收藏与历史")
            // 在持锁状态下立即吊销本地凭据，避免延迟网络登出完成时误删后来登录的新会话
            runCatching { vault.deleteSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON) }
            currentCred
        }
        if (credToRevoke != null) {
            runCatching { accountService.signOut(credToRevoke) }
        }
    }

    private suspend fun persistAccountDocument(
        uid: String,
        document: UserDataDocument,
        syncEnabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        document.validate()
        val encoded = UserDataDocument.wireJson.encodeToString(document)
        val stateEntity = AccountSyncStateEntity(
            accountUid = uid,
            syncEnabled = syncEnabled,
            historyClearedAtMs = document.historyClearedAt,
            lastSyncedDocumentJson = encoded,
            updatedAtMs = nowMs.toDouble(),
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
