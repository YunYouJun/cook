package cn.yunyoujun.cook.data.remote

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.platform.WeChatAuthorization
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeChatContractsTest {
    @Test
    fun androidLoginAndBindingUseTheExistingCookClientAndReturnTheOriginalSubject() = runBlocking {
        val transport = Transport(
            success("""{"ok":true,"ticket":"issued-ticket","subject":"existing-user"}"""),
            success(token("existing-user")),
            success("""{"ok":true}"""),
        )
        val service = YunlefunAccountService(transport)
        val signedIn = service.signInWithWeChat("wechat-code", "saved-device")
        assertEquals("existing-user", signedIn.subject)
        service.bindWeChat("new-code", signedIn)

        val (exchangeRequest, tokenRequest, bindRequest) = transport.requests
        assertEquals(YunlefunAccountService.TICKET_URL, exchangeRequest.url)
        assertEquals(YunlefunAccountService.COOK_ORIGIN, exchangeRequest.headers["Origin"])
        assertNull(exchangeRequest.headers["Authorization"])
        assertEquals(mapOf(
            "action" to "exchangeCookWeChatCode", "clientId" to "cook-mobile", "code" to "wechat-code",
        ), exchangeRequest.fields)
        assertEquals(YunlefunAccountService.GATEWAY + "signin/custom", tokenRequest.url)
        assertEquals("saved-device", tokenRequest.headers["x-device-id"])
        assertEquals(mapOf("provider_id" to "custom", "ticket" to "issued-ticket"), tokenRequest.fields)
        assertEquals(YunlefunAccountService.TICKET_URL, bindRequest.url)
        assertEquals("Bearer access", bindRequest.headers["Authorization"])
        assertEquals("saved-device", bindRequest.headers["x-device-id"])
        assertEquals(mapOf(
            "action" to "bindCookWeChat", "clientId" to "cook-mobile", "code" to "new-code",
        ), bindRequest.fields)
    }

    @Test
    fun signupVerifiesCaptchaAndSmsBeforeDistinguishingAnExistingAccount() = runBlocking {
        val transport = Transport(
            HttpResponsePayload(400, """{"ok":false,"reason":"signup_captcha_required"}"""),
            success("""{"ok":true,"image":"data:image/png;base64,cGljdHVyZQ==","challenge":"captcha"}"""),
            success("""{"ok":true,"challenge":"sms"}"""),
            success("""{"ok":true,"next":"existing"}"""),
        )
        val service = YunlefunAccountService(transport)
        val device = "signup-device"
        val phone = "13900000000"
        val failure = runCatching { service.sendWeChatSignupSms(phone, null, device) }.exceptionOrNull()
        assertEquals(WeChatAccountException.Reason.CAPTCHA_REQUIRED, (failure as WeChatAccountException).reason)
        val captcha = service.weChatSignupCaptcha(device)
        val challenge = service.sendWeChatSignupSms(phone, captcha.challenge to "abcD", device)
        val next = service.verifyWeChatSignupSms(challenge, "123456", device)
        assertEquals(WeChatSignupNext.ExistingAccount, next)
        assertEquals(listOf("sendCookSignupSms", "cookSignupCaptcha", "sendCookSignupSms", "verifyCookSignupSms"),
            transport.requests.map { it.fields["action"] })
        assertTrue(transport.requests.all { it.headers["x-device-id"] == device && it.headers["Authorization"] == null })
        assertEquals("captcha", transport.requests[2].fields["captchaChallenge"])
        assertEquals("abcD", transport.requests[2].fields["captchaAnswer"])
    }

    @Test
    fun onlyExplicitRegistrationRedeemsTheSignupProofAndRejectsCrossAccountTickets() = runBlocking {
        val transport = Transport(
            success("""{"ok":true,"next":"confirm","proof":"verified-phone"}"""),
            success("""{"ok":true,"ticket":"issued-ticket","subject":"new-user"}"""),
            success(token("unexpected-user")),
            success("{}"),
        )
        val service = YunlefunAccountService(transport)
        val confirm = service.verifyWeChatSignupSms("sms", "123456", "device") as WeChatSignupNext.Confirm
        assertEquals(1, transport.requests.size)
        val failure = runCatching {
            service.registerWithWeChat(confirm.proof, "wechat-code", "device")
        }.exceptionOrNull()
        assertEquals(WeChatAccountException.Reason.ACCOUNT_CREATED, (failure as WeChatAccountException).reason)
        assertEquals(mapOf("action" to "registerCookWeChat", "clientId" to "cook-mobile", "proof" to "verified-phone",
            "code" to "wechat-code"), transport.requests[1].fields)
        assertEquals(YunlefunAccountService.GATEWAY + "user/signout", transport.requests.last().url)
    }

    @Test
    fun authorizationRejectsUnsolicitedReplayMismatchedAndExpiredCallbacks() = runBlocking {
        var clock = 500L
        val driver = Driver()
        val authorization = WeChatAuthorization(true, driver, { clock }, Dispatchers.Unconfined)
        authorization.handle(Intent())
        assertEquals(0, driver.handled)

        val first = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        val firstState = driver.state
        driver.response = Triple(0, "wrong-state", "wrong-code")
        authorization.handle(Intent())
        assertFalse(first.isCompleted)
        driver.response = Triple(0, firstState, "correct-code")
        authorization.handle(Intent())
        assertEquals("correct-code", first.await())

        val second = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        assertNotEquals(firstState, driver.state)
        authorization.handle(Intent()) // previous request's callback
        assertFalse(second.isCompleted)
        driver.response = Triple(-2, driver.state, null)
        authorization.handle(Intent())
        assertNull(second.await())

        val expired = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authorization.authorize() } }
        clock += 180_000L
        driver.response = Triple(0, driver.state, "too-late")
        authorization.handle(Intent())
        val failure = expired.await().exceptionOrNull() as WeChatAccountException
        assertEquals(WeChatAccountException.Reason.AUTHORIZATION_FAILED, failure.reason)

        val abandoned = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        abandoned.cancelAndJoin()
        val previousHandled = driver.handled
        authorization.handle(Intent())
        assertEquals(previousHandled, driver.handled)

        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        assertTrue(authorization.awaiting.value)
        authorization.cancelWaiting()
        assertNull(cancelled.await())
        assertFalse(authorization.awaiting.value)
    }

    @Test
    fun bindingAbandonsTheCodeIfTheCurrentAccountChangesDuringAuthorization() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val vault = MemoryVault()
        val transport = Transport(
            success("""{"ok":true,"ticket":"issued","subject":"current-user"}"""),
            success(token("current-user")),
            success("""{"sub":"current-user","status":"ACTIVE"}"""),
            success("""{"membership":{"isActive":false}}"""),
            success("{}"),
        )
        try {
            val sessions = YunlefunSessionManager(
                vault, database, YunlefunAccountService(transport), membershipService = MembershipService(transport),
            )
            assertTrue(sessions.signInWithWeChat { "sign-in-code" })
            assertEquals("current-user", sessions.state.value.account?.id)
            assertFalse(sessions.state.value.syncEnabled)
            assertTrue(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON).orEmpty().contains("current-user"))
            val bind = runCatching {
                sessions.bindWeChat {
                    sessions.signOut()
                    "old-account-code"
                }
            }
            assertTrue(bind.isFailure)
            assertNull(sessions.state.value.credential)
            assertTrue(transport.requests.none { it.fields["action"] == "bindCookWeChat" })
        } finally {
            database.close()
        }
    }

    private class Driver : WeChatAuthorization.Driver {
        lateinit var state: String
        var handled = 0
        var response = Triple<Int, String?, String?>(0, null, null)
        override fun isInstalled() = true
        override fun send(state: String): Boolean {
            this.state = state
            return true
        }
        override fun handle(intent: Intent, onResponse: (Int, String?, String?) -> Unit) {
            handled++
            onResponse(response.first, response.second, response.third)
        }
    }

    private class Transport(vararg responses: HttpResponsePayload) : HttpTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<Request>()
        data class Request(val url: String, val headers: Map<String, String>, val fields: Map<String, String>)
        override suspend fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            jsonBody: String?,
            maxResponseBytes: Int,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): HttpResponsePayload {
            val fields = jsonBody?.let { Json.parseToJsonElement(it).jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content } }
            requests.add(Request(url, headers, fields.orEmpty()))
            return responses.removeFirst()
        }
    }

    private class MemoryVault : SecretVault {
        private val values = mutableMapOf<String, String>()
        override fun readSecret(key: String): String? = values[key]
        override fun writeSecret(key: String, value: String) { values[key] = value }
        override fun deleteSecret(key: String) { values.remove(key) }
    }

    private companion object {
        fun success(body: String) = HttpResponsePayload(200, body)
        fun token(subject: String) =
            """{"access_token":"access","refresh_token":"refresh","sub":"$subject","expires_in":3600}"""
    }
}
