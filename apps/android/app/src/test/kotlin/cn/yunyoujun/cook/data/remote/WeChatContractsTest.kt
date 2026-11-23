package cn.yunyoujun.cook.data.remote

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.platform.WeChatAuthorization
import cn.yunyoujun.cook.platform.WeChatAuthorization.ResponseStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

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
    fun signupStopsBeforeTicketExchangeWhenTheNewAccountIsNotIdentified() = runBlocking {
        val transport = Transport(
            success("""{"ok":true,"ticket":"issued-ticket"}"""),
            success(token("other-user")),
        )

        val failure = runCatching {
            YunlefunAccountService(transport).registerWithWeChat("fixture-proof", "fixture-code", "fixture-device")
        }.exceptionOrNull() as? WeChatAccountException

        assertEquals(WeChatAccountException.Reason.ACCOUNT_CREATED, failure?.reason)
        assertEquals(listOf("registerCookWeChat"), transport.requests.map { it.fields["action"] })
    }

    @Test
    fun signupDistinguishesUnconfirmedOutcomesFromExplicitRejections() = runBlocking {
        val cases = listOf(
            HttpResponsePayload(408, "request timeout") to WeChatAccountException.Reason.SIGNUP_UNCONFIRMED,
            HttpResponsePayload(502, "gateway response") to WeChatAccountException.Reason.SIGNUP_UNCONFIRMED,
            HttpResponsePayload(200, "incomplete response") to WeChatAccountException.Reason.SIGNUP_UNCONFIRMED,
            HttpResponsePayload(503, """{"ok":false,"reason":"not_configured"}""") to WeChatAccountException.Reason.UNAVAILABLE,
            HttpResponsePayload(409, """{"ok":false,"reason":"signup_existing_account"}""") to WeChatAccountException.Reason.EXISTING_ACCOUNT,
            HttpResponsePayload(429, """{"ok":false,"reason":"rate_limited"}""") to WeChatAccountException.Reason.RATE_LIMITED,
        )
        for ((response, expected) in cases) {
            val transport = Transport(response)
            val failure = runCatching {
                YunlefunAccountService(transport).registerWithWeChat("fixture-proof", "fixture-code", "fixture-device")
            }.exceptionOrNull() as WeChatAccountException
            assertEquals(expected, failure.reason)
            assertEquals("registerCookWeChat", transport.requests.single().fields["action"])
        }
    }

    @Test
    fun authorizationRejectsUnsolicitedReplayMismatchedAndExpiredCallbacks() = runBlocking {
        var clock = 500L
        val driver = Driver()
        val authorization = WeChatAuthorization(driver, { clock }, Dispatchers.Unconfined)
        authorization.handle(Intent())
        assertEquals(0, driver.handled)

        val first = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        val firstState = driver.state
        driver.response = Triple(ResponseStatus.AUTHORIZED, "wrong-state", "wrong-code")
        authorization.handle(Intent())
        assertFalse(first.isCompleted)
        driver.response = Triple(ResponseStatus.AUTHORIZED, firstState, "correct-code")
        authorization.handle(Intent())
        assertEquals("correct-code", first.await())

        val second = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        assertNotEquals(firstState, driver.state)
        authorization.handle(Intent()) // previous request's callback
        assertFalse(second.isCompleted)
        driver.response = Triple(ResponseStatus.CANCELLED, driver.state, null)
        authorization.handle(Intent())
        assertNull(second.await())

        val expired = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authorization.authorize() } }
        clock += 180_000L
        driver.response = Triple(ResponseStatus.AUTHORIZED, driver.state, "too-late")
        authorization.handle(Intent())
        val failure = expired.await().exceptionOrNull() as WeChatAccountException
        assertEquals(WeChatAccountException.Reason.AUTHORIZATION_FAILED, failure.reason)

        val abandoned = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        abandoned.cancelAndJoin()
        val previousHandled = driver.handled
        authorization.handle(Intent())
        assertEquals(previousHandled, driver.handled)

        val cancelled = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        assertTrue(authorization.isAwaiting)
        cancelled.cancelAndJoin()
        assertTrue(cancelled.isCancelled)
        assertFalse(authorization.isAwaiting)
    }

    @Test
    fun returningToTheAppEndsAnUnavailableOrExpiredAuthorization() = runBlocking {
        var clock = 500L
        val driver = Driver()
        val authorization = WeChatAuthorization(driver, { clock }, Dispatchers.Unconfined)
        val first = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authorization.authorize() } }
        val firstState = driver.state
        authorization.onForeground()
        assertFalse(first.isCompleted)

        driver.installed = false
        authorization.onForeground()
        val unavailable = withTimeout(1_000) { first.await() }.exceptionOrNull() as WeChatAccountException
        assertEquals(WeChatAccountException.Reason.UNAVAILABLE, unavailable.reason)
        assertFalse(authorization.isAwaiting)

        driver.installed = true
        val second = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authorization.authorize() } }
        driver.response = Triple(ResponseStatus.AUTHORIZED, firstState, "old-code")
        authorization.handle(Intent())
        clock += 179_999L
        authorization.onForeground()
        assertFalse(second.isCompleted)
        clock++
        authorization.onForeground()
        val expired = withTimeout(1_000) { second.await() }.exceptionOrNull() as WeChatAccountException
        assertEquals(WeChatAccountException.Reason.AUTHORIZATION_FAILED, expired.reason)
        assertFalse(authorization.isAwaiting)

        val next = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        driver.response = Triple(ResponseStatus.AUTHORIZED, driver.state, "current-code")
        authorization.handle(Intent())
        authorization.onForeground()
        assertEquals("current-code", withTimeout(1_000) { next.await() })
    }

    @Test
    fun matchingCallbackCannotCompleteAuthorizationWhenWeChatWasDisabledWhileAway() = runBlocking {
        val driver = Driver()
        val authorization = WeChatAuthorization(driver, dispatcher = Dispatchers.Unconfined)
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { authorization.authorize() } }
        val oldState = driver.state
        driver.installed = false
        driver.response = Triple(ResponseStatus.AUTHORIZED, oldState, "code-before-resume")
        // The callback Activity can be created before the original Activity resumes.
        authorization.handle(Intent())
        val failure = withTimeout(1_000) { waiting.await() }.exceptionOrNull() as? WeChatAccountException
        assertEquals(WeChatAccountException.Reason.UNAVAILABLE, failure?.reason)
        assertFalse(authorization.isAwaiting)

        driver.installed = true
        val next = async(start = CoroutineStart.UNDISPATCHED) { authorization.authorize() }
        authorization.handle(Intent())
        assertFalse(next.isCompleted)
        driver.response = Triple(ResponseStatus.AUTHORIZED, driver.state, "new-code")
        authorization.handle(Intent())
        assertEquals("new-code", withTimeout(1_000) { next.await() })
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

    @Test
    @Config(instrumentedPackages = ["cn.yunyoujun.cook.data.remote"])
    fun bindingRenewsTheSameSessionBeforeAndAfterTheWeChatHandoff() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val vault = MemoryVault().apply {
            val saved = YunlefunCredential("expired", "refresh", "current-user", 1, "current-device")
            writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(saved))
        }
        val transport = Transport(
            success("""{"access_token":"first-renewal","refresh_token":"next-refresh","sub":"current-user","expires_in":60}"""),
            success("""{"access_token":"return-renewal","refresh_token":"last-refresh","sub":"current-user","expires_in":3600}"""),
            success("""{"ok":true}"""),
        )
        try {
            val sessions = YunlefunSessionManager(vault, database, YunlefunAccountService(transport))
            sessions.awaitInitialHydration()
            val identity = sessions.state.value.membershipSession
            assertTrue(sessions.bindWeChat {
                assertEquals("first-renewal", sessions.state.value.credential?.accessToken)
                assertEquals(listOf(YunlefunAccountService.GATEWAY + "token"), transport.requests.map { it.url })
                ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
                assertFalse(requireNotNull(sessions.state.value.credential).isValid())
                "new-binding-code"
            })

            val (firstRefresh, secondRefresh, binding) = transport.requests
            assertEquals("refresh", firstRefresh.fields["refresh_token"])
            assertEquals("next-refresh", secondRefresh.fields["refresh_token"])
            assertEquals("Bearer return-renewal", binding.headers["Authorization"])
            assertEquals("current-device", binding.headers["x-device-id"])
            assertEquals("bindCookWeChat", binding.fields["action"])
            assertEquals("new-binding-code", binding.fields["code"])
            assertEquals(identity, sessions.state.value.membershipSession)
            assertEquals("return-renewal", sessions.state.value.credential?.accessToken)
            val saved = requireNotNull(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON))
            assertEquals("last-refresh", Json.decodeFromString<YunlefunCredential>(saved).refreshToken)
        } finally {
            database.close()
        }
    }

    @Test
    fun bindingRenewsRejectedSessionAndRequiresFreshWeChatConsent() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val vault = MemoryVault().apply {
            val saved = YunlefunCredential(
                "rejected-session", "original-refresh", "current-user", System.currentTimeMillis() + 3_600_000, "current-device",
            )
            writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(saved))
        }
        val transport = Transport(
            HttpResponsePayload(401, """{"ok":false,"reason":"not_authenticated"}"""),
            success(token("current-user")),
            success("""{"ok":true}"""),
        )
        try {
            val sessions = YunlefunSessionManager(vault, database, YunlefunAccountService(transport))
            sessions.awaitInitialHydration()
            val identity = sessions.state.value.membershipSession

            val failure = runCatching { sessions.bindWeChat { "first-binding-code" } }.exceptionOrNull()
            assertEquals(2, transport.requests.size)
            assertEquals(WeChatAccountException.Reason.BINDING_REAUTHORIZE, (failure as WeChatAccountException).reason)
            val (rejectedBinding, renewal) = transport.requests
            assertEquals("first-binding-code", rejectedBinding.fields["code"])
            assertEquals("Bearer rejected-session", rejectedBinding.headers["Authorization"])
            assertEquals(YunlefunAccountService.GATEWAY + "token", renewal.url)
            assertEquals("original-refresh", renewal.fields["refresh_token"])
            assertEquals(identity, sessions.state.value.membershipSession)
            val saved = requireNotNull(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON))
            val renewed = Json.decodeFromString<YunlefunCredential>(saved)
            assertEquals("access", renewed.accessToken)
            assertEquals("refresh", renewed.refreshToken)

            assertTrue(sessions.bindWeChat { "fresh-binding-code" })
            assertEquals(3, transport.requests.size)
            val binding = transport.requests.last()
            assertEquals("bindCookWeChat", binding.fields["action"])
            assertEquals("fresh-binding-code", binding.fields["code"])
            assertEquals("Bearer access", binding.headers["Authorization"])
            assertEquals("current-device", binding.headers["x-device-id"])
            assertEquals(identity, sessions.state.value.membershipSession)
        } finally {
            database.close()
        }
    }

    @Test
    fun bindingWithInvalidWeChatCodeKeepsExistingSession() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val credential = YunlefunCredential(
            "existing-session", "original-refresh", "current-user", System.currentTimeMillis() + 3_600_000, "current-device",
        )
        val vault = MemoryVault().apply {
            writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(credential))
        }
        val transport = Transport(HttpResponsePayload(401, """{"ok":false,"reason":"wechat_code_invalid"}"""))
        try {
            val sessions = YunlefunSessionManager(vault, database, YunlefunAccountService(transport))
            sessions.awaitInitialHydration()

            val failure = runCatching { sessions.bindWeChat { "invalid-code" } }.exceptionOrNull()
            assertEquals(WeChatAccountException.Reason.AUTHORIZATION_FAILED, (failure as WeChatAccountException).reason)
            assertEquals("bindCookWeChat", transport.requests.single().fields["action"])
            assertEquals(credential, sessions.state.value.credential)
        } finally {
            database.close()
        }
    }

    @Test
    fun bindingDoesNotOpenWeChatIfRenewalReturnsAnotherAccount() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val vault = MemoryVault().apply {
            val saved = YunlefunCredential("expired", "refresh", "current-user", 1, "current-device")
            writeSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON, Json.encodeToString(saved))
        }
        val transport = Transport(success(token("other-user")))
        try {
            val sessions = YunlefunSessionManager(vault, database, YunlefunAccountService(transport))
            sessions.awaitInitialHydration()
            val result = runCatching { sessions.bindWeChat { error("WeChat must stay closed") } }
            assertTrue(result.exceptionOrNull() is YunlefunAccountException.ExpiredSession)
            assertEquals("current-user", sessions.state.value.credential?.subject)
            assertEquals(YunlefunAccountService.GATEWAY + "token", transport.requests.single().url)
        } finally {
            database.close()
        }
    }

    private class Driver : WeChatAuthorization.Driver {
        lateinit var state: String
        var installed = true
        var handled = 0
        var response = Triple<ResponseStatus, String?, String?>(ResponseStatus.AUTHORIZED, null, null)
        override fun isInstalled() = installed
        override fun send(state: String): Boolean {
            this.state = state
            return true
        }
        override fun handle(intent: Intent, onResponse: (ResponseStatus, String?, String?) -> Unit) {
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
