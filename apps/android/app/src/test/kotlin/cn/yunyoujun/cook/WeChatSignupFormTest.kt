package cn.yunyoujun.cook

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.WeChatAccountException
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.WECHAT_PACKAGE
import cn.yunyoujun.cook.platform.WeChatAuthorization
import cn.yunyoujun.cook.platform.WeChatAuthorization.ResponseStatus
import cn.yunyoujun.cook.ui.navigation.CookNavKey
import cn.yunyoujun.cook.ui.screens.WeChatAccountActions
import cn.yunyoujun.cook.ui.screens.WeChatSignupForm
import cn.yunyoujun.cook.ui.state.SignupFormState
import cn.yunyoujun.cook.ui.state.WeChatSignupViewModel
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WeChatSignupFormTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun activityRecreationKeepsVerificationAndPendingRegistrationUntilTheDestinationCloses() {
        val devices = mutableListOf<String>()
        var registration: Pair<String, String>? = null
        val authorizing = CompletableDeferred<Unit>()
        val models = mutableListOf<WeChatSignupViewModel>()
        val weChatAvailable = mutableStateOf(true)
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                jsonBody: String?,
                maxResponseBytes: Int,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
            ): HttpResponsePayload {
                devices += requireNotNull(headers["x-device-id"])
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                return when (body.getValue("action").jsonPrimitive.content) {
                    "sendCookSignupSms" -> {
                        assertEquals("13900000000", body.getValue("phone").jsonPrimitive.content)
                        HttpResponsePayload(200, """{"ok":true,"challenge":"sms"}""")
                    }
                    "verifyCookSignupSms" -> HttpResponsePayload(200, """{"ok":true,"next":"confirm","proof":"verified"}""")
                    else -> error("Sign-up must wait for the explicit action")
                }
            }
        }
        val service = YunlefunAccountService(transport)
        val content: @Composable () -> Unit = {
            CookTheme {
                val backStack = rememberNavBackStack(CookNavKey.SettingsTab)
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = entryProvider {
                        entry<CookNavKey.SettingsTab> {
                            Button(onClick = { backStack.add(CookNavKey.WeChatSignup) }) { Text("使用微信注册") }
                        }
                        entry<CookNavKey.WeChatSignup> {
                            val model = viewModel {
                                WeChatSignupViewModel(service, authorize = {
                                    authorizing.await()
                                    null
                                }) { proof, id, authorize ->
                                    registration = proof to id
                                    authorize()
                                }.also(models::add)
                            }
                            WeChatSignupForm(
                                model,
                                onBack = { backStack.removeLastOrNull() },
                                weChatAvailable = weChatAvailable.value,
                            )
                        }
                    },
                )
            }
        }
        compose.setContent(content)
        compose.onNodeWithText("使用微信注册").performClick()
        val phoneField = compose.onNodeWithText("中国大陆手机号")
        phoneField.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.PhoneNumberNational))
        for (invalid in listOf("+86 139 0000 00001", "13900000000 ext 1")) {
            phoneField.performTextReplacement(invalid)
            compose.onNodeWithText("发送短信验证码").assertIsNotEnabled()
            phoneField.performImeAction()
            compose.runOnIdle {
                assertEquals(invalid, models.single().state.value.phone)
                assertTrue(devices.isEmpty())
            }
        }
        phoneField.performTextReplacement("+86 (139) 0000-0000")
        compose.runOnIdle { weChatAvailable.value = false }
        compose.onNodeWithText("发送短信验证码").assertIsNotEnabled()
        phoneField.performImeAction()
        compose.runOnIdle {
            assertTrue(devices.isEmpty())
            weChatAvailable.value = true
        }
        phoneField.performImeAction()
        awaitText("短信验证码")
        val otpField = compose.onNodeWithText("短信验证码")
        otpField.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentType, ContentType.SmsOtpCode))
        for (invalid in listOf("1234567", "12A345")) {
            otpField.performTextReplacement(invalid)
            compose.onNodeWithText("验证手机号").assertIsNotEnabled()
            otpField.performImeAction()
            compose.runOnIdle {
                assertEquals(invalid, models.single().state.value.otp)
                assertEquals(1, devices.size)
            }
        }
        otpField.performTextReplacement("123")
        otpField.performImeAction()

        recreate(content)
        awaitText("短信验证码")
        compose.runOnIdle {
            assertEquals("123", models.single().state.value.otp)
            assertEquals(1, devices.size)
        }
        compose.runOnIdle { weChatAvailable.value = false }
        otpField.performTextReplacement("123\u202f456")
        otpField.performImeAction()
        awaitText("创建账号并授权微信")
        compose.onNodeWithText("创建账号并授权微信").assertIsNotEnabled()
        compose.runOnIdle {
            assertNull(registration)
            assertEquals(2, devices.size)
            assertEquals(devices.first(), devices.last())
            weChatAvailable.value = true
        }
        compose.onNodeWithText("创建账号并授权微信").performClick()
        awaitText("等待微信授权…")
        recreate(content)
        awaitText("等待微信授权…")
        compose.runOnIdle {
            assertEquals("verified" to devices.first(), registration)
            assertEquals(1, models.size)
        }
        authorizing.complete(Unit)
        compose.onNodeWithText("返回").performClick()
        awaitText("使用微信注册")
        compose.runOnIdle { assertEquals(SignupFormState(), models.first().state.value) }
        compose.onNodeWithText("使用微信注册").performClick()
        awaitText("中国大陆手机号")
        compose.runOnIdle { assertEquals(SignupFormState(), models.last().state.value) }
    }

    @Test
    fun systemBackCancelsLoginAndASecondAuthorizationSurvivesActivityRecreation() {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), CookDatabase::class.java,
        ).build()
        val secret = AtomicReference<String?>()
        val vault = object : SecretVault {
            override fun readSecret(key: String) = secret.get()
            override fun writeSecret(key: String, value: String) { secret.set(value) }
            override fun deleteSecret(key: String) { secret.set(null) }
        }
        val exchanges = AtomicInteger(0)
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                jsonBody: String?,
                maxResponseBytes: Int,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
            ): HttpResponsePayload {
                val reply = when {
                    url == YunlefunAccountService.TICKET_URL -> {
                        val fields = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                        assertEquals("exchangeCookWeChatCode", fields.getValue("action").jsonPrimitive.content)
                        assertEquals("fixture-accepted-code", fields.getValue("code").jsonPrimitive.content)
                        exchanges.incrementAndGet()
                        """{"ok":true,"ticket":"fixture-ticket","subject":"fixture-member"}"""
                    }
                    url.endsWith("/signin/custom") ->
                        """{"access_token":"access","refresh_token":"refresh","sub":"fixture-member","expires_in":3600}"""
                    url.endsWith("/user/me") -> """{"sub":"fixture-member","status":"ACTIVE"}"""
                    url.endsWith("/user/signout") -> "{}"
                    url.endsWith("/account-api") -> """{"membership":{"isActive":false}}"""
                    else -> error("Unexpected authorization test request")
                }
                return HttpResponsePayload(200, reply)
            }
        }
        val sessions = YunlefunSessionManager(
            vault, database, YunlefunAccountService(transport), membershipService = MembershipService(transport),
        )
        val sentStates = mutableListOf<String>()
        var receivedState: String? = null
        val installed = AtomicBoolean(true)
        val driver = object : WeChatAuthorization.Driver {
            override fun isInstalled() = installed.get()
            override fun send(state: String): Boolean {
                sentStates += state
                return true
            }
            override fun handle(intent: Intent, onResponse: (ResponseStatus, String?, String?) -> Unit) {
                onResponse(ResponseStatus.AUTHORIZED, receivedState, "fixture-accepted-code")
            }
        }
        val authorization = WeChatAuthorization(driver)
        val content: @Composable () -> Unit = {
            CookTheme {
                val account by sessions.state.collectAsState()
                Column {
                    Text(account.credential?.subject.orEmpty())
                    WeChatAccountActions(authorization, sessions, account.credential?.subject, account.revision, onOpenSignup = {})
                }
            }
        }
        try {
            compose.setContent(content)
            compose.onNodeWithText("使用微信登录").performClick()
            compose.runOnIdle { assertEquals(emptyList<String>(), sentStates) }
            compose.onNodeWithText("继续前往微信").performClick()
            awaitText("等待微信授权…")
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(5_000) { !authorization.isAwaiting }
            compose.onNodeWithText("使用微信登录").assertIsEnabled()
            compose.onNodeWithText("使用微信登录").performClick()
            compose.onNodeWithText("继续前往微信").performClick()
            awaitText("等待微信授权…")

            val scenario = compose.activityRule.scenario
            scenario.onActivity {
                installed.set(false)
                it.sendBroadcast(Intent(Intent.ACTION_PACKAGES_SUSPENDED)
                    .putExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST, arrayOf(WECHAT_PACKAGE)))
            }
            awaitText(WeChatAccountException.Reason.UNAVAILABLE.message)
            compose.onNodeWithText("使用微信登录").assertDoesNotExist()
            assertEquals(0, exchanges.get())
            assertNull(sessions.state.value.credential)

            scenario.onActivity {
                installed.set(true)
                it.sendBroadcast(Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:$WECHAT_PACKAGE")))
            }
            awaitText("使用微信登录")
            compose.onNodeWithText("使用微信登录").assertIsEnabled().performClick()
            compose.onNodeWithText("继续前往微信").performClick()
            awaitText("等待微信授权…")
            recreate(content)
            awaitText("等待微信授权…")
            compose.runOnIdle {
                receivedState = sentStates[sentStates.lastIndex - 1]
                authorization.handle(Intent())
                assertEquals(0, exchanges.get())
                receivedState = sentStates.last()
                authorization.handle(Intent())
            }

            awaitText("fixture-member")
            assertEquals(1, exchanges.get())
            assertEquals("fixture-member", sessions.state.value.credential?.subject)

            compose.onNodeWithText("绑定微信").performClick()
            compose.onNodeWithText("继续前往微信").performClick()
            awaitText("等待微信授权…")
            val previousRevision = sessions.state.value.revision
            runBlocking { sessions.signOut() }
            assertNull(sessions.state.value.credential)
            assertTrue(sessions.state.value.revision > previousRevision)
            awaitText("使用微信登录")
            compose.waitUntil(5_000) { !authorization.isAwaiting }
            compose.runOnIdle {
                assertEquals(4, sentStates.distinct().size)
                receivedState = sentStates.last()
                authorization.handle(Intent())
                assertEquals(1, exchanges.get())
                assertNull(secret.get())
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun captchaOnResendKeepsThePreviousSmsUsableUntilAReplacementArrives() {
        val sendsByDevice = mutableMapOf<String, Int>()
        val verifiedChallenges = mutableListOf<String>()
        val picture = "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAAAAAA6mKC9AAAAHElEQVR4nGNgYGRiZmFlY+fg5OLm4eXjZxjZAgDn/QeBFYInPwAAAABJRU5ErkJggg=="
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                jsonBody: String?,
                maxResponseBytes: Int,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
            ): HttpResponsePayload {
                val device = requireNotNull(headers["x-device-id"])
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                return when (body.getValue("action").jsonPrimitive.content) {
                    "sendCookSignupSms" -> {
                        val attempt = sendsByDevice.getOrDefault(device, 0) + 1
                        sendsByDevice[device] = attempt
                        when (attempt) {
                            1 -> HttpResponsePayload(200, """{"ok":true,"challenge":"original-sms"}""")
                            2 -> HttpResponsePayload(400, """{"reason":"signup_captcha_required"}""")
                            3 -> {
                                assertEquals("captcha-challenge", body.getValue("captchaChallenge").jsonPrimitive.content)
                                assertEquals("ABCD", body.getValue("captchaAnswer").jsonPrimitive.content)
                                HttpResponsePayload(200, """{"ok":true,"challenge":"replacement-sms"}""")
                            }
                            else -> error("Unexpected resend")
                        }
                    }
                    "cookSignupCaptcha" -> HttpResponsePayload(
                        200, """{"ok":true,"image":"data:image/png;base64,$picture","challenge":"captcha-challenge"}""",
                    )
                    "verifyCookSignupSms" -> {
                        val challenge = body.getValue("challenge").jsonPrimitive.content
                        assertEquals(if (challenge == "original-sms") "123456" else "987654", body.getValue("otp").jsonPrimitive.content)
                        verifiedChallenges += challenge
                        HttpResponsePayload(200, """{"ok":true,"next":"confirm","proof":"verified"}""")
                    }
                    else -> error("Signup must wait for explicit confirmation")
                }
            }
        }
        compose.setContent {
            CookTheme {
                val model = viewModel {
                    WeChatSignupViewModel(YunlefunAccountService(transport), authorize = { error("Unexpected authorization") }) { _, _, _ ->
                        error("Unexpected signup")
                    }
                }
                WeChatSignupForm(model, onBack = {})
            }
        }
        compose.onNodeWithText("中国大陆手机号").performTextInput("13900000000")
        for (verifyOriginal in listOf(true, false)) {
            compose.onNodeWithText("发送短信验证码").performClick()
            awaitText("短信验证码")
            val otp = compose.onNodeWithText("短信验证码")
            otp.performTextInput("123456")
            compose.runOnIdle { ShadowSystemClock.advanceBy(Duration.ofSeconds(61)) }
            compose.mainClock.advanceTimeBy(300)
            val resend = compose.onNodeWithText("重新发送")
            resend.performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("输入图片验证码").fetchSemanticsNodes().isNotEmpty()
            }
            val captcha = compose.onNodeWithText("输入图片验证码")
            captcha.performScrollTo().assertIsDisplayed()
            resend.performScrollTo().assertIsNotEnabled()
            val verify = compose.onNodeWithText("验证手机号")
            verify.performScrollTo().assertIsEnabled()
            if (!verifyOriginal) {
                captcha.performScrollTo().performTextInput("ABCD")
                resend.performScrollTo().assertIsEnabled()
                captcha.performScrollTo().performImeAction()
                verify.performScrollTo().assertIsNotEnabled()
                captcha.assertDoesNotExist()
                otp.performScrollTo().performTextInput("987654")
            }
            verify.performScrollTo().performClick()
            awaitText("创建账号并授权微信")
            captcha.assertDoesNotExist()
            if (verifyOriginal) compose.onNodeWithText("修改手机号").performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(2, 3), sendsByDevice.values.toList())
            assertEquals(listOf("original-sms", "replacement-sms"), verifiedChallenges)
        }
    }

    @Test
    fun authorizedSignupSignsIntoTheVerifiedAccountAfterRecreationAndRestoresItFromTheVault() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CookDatabase::class.java).build()
        val saved = ConcurrentHashMap<String, String>()
        val vault = object : SecretVault {
            override fun readSecret(key: String) = saved[key]
            override fun writeSecret(key: String, value: String) { saved[key] = value }
            override fun deleteSecret(key: String) { saved.remove(key) }
        }
        val uid = "verified-signup-user"
        val device = AtomicReference<String>()
        val registrations = AtomicInteger()
        val exchanges = AtomicInteger()
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String, url: String, headers: Map<String, String>, jsonBody: String?,
                maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
            ): HttpResponsePayload {
                val body = when {
                    url == YunlefunAccountService.TICKET_URL -> {
                        val fields = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                        when (fields.getValue("action").jsonPrimitive.content) {
                            "sendCookSignupSms" -> {
                                device.set(requireNotNull(headers["x-device-id"]))
                                assertEquals("13900000000", fields.getValue("phone").jsonPrimitive.content)
                                """{"ok":true,"challenge":"fixture-sms"}"""
                            }
                            "verifyCookSignupSms" -> {
                                assertEquals(device.get(), headers["x-device-id"])
                                assertEquals("123456", fields.getValue("otp").jsonPrimitive.content)
                                """{"ok":true,"next":"confirm","proof":"fixture-proof"}"""
                            }
                            "registerCookWeChat" -> {
                                assertEquals(device.get(), headers["x-device-id"])
                                assertEquals("fixture-proof", fields.getValue("proof").jsonPrimitive.content)
                                assertEquals("fixture-code", fields.getValue("code").jsonPrimitive.content)
                                registrations.incrementAndGet()
                                """{"ok":true,"ticket":"created-ticket","subject":"$uid"}"""
                            }
                            else -> error("Unexpected sign-up action")
                        }
                    }
                    url.endsWith("/signin/custom") -> {
                        assertEquals(device.get(), headers["x-device-id"])
                        exchanges.incrementAndGet()
                        """{"access_token":"access","refresh_token":"refresh","sub":"$uid","expires_in":3600}"""
                    }
                    url.endsWith("/user/me") -> """{"sub":"$uid","status":"ACTIVE"}"""
                    url.endsWith("/account-api") -> """{"membership":{"isActive":false}}"""
                    else -> error("Unexpected session request")
                }
                return HttpResponsePayload(200, body)
            }
        }
        val service = YunlefunAccountService(transport)
        fun newSessionManager() = YunlefunSessionManager(vault, database, service, membershipService = MembershipService(transport))
        val sessions = newSessionManager()
        var sentState: String? = null
        val authorization = WeChatAuthorization(object : WeChatAuthorization.Driver {
            override fun isInstalled() = true
            override fun send(state: String): Boolean {
                sentState = state
                return true
            }
            override fun handle(intent: Intent, onResponse: (ResponseStatus, String?, String?) -> Unit) {
                onResponse(ResponseStatus.AUTHORIZED, sentState, "fixture-code")
            }
        })
        val content: @Composable () -> Unit = {
            CookTheme {
                val account by sessions.state.collectAsState()
                val signedIn = account.account
                if (signedIn == null) {
                    val model = viewModel {
                        WeChatSignupViewModel(service, authorization::authorize) { proof, deviceId, authorize ->
                            sessions.registerWithWeChat(proof, deviceId, authorize)
                        }
                    }
                    WeChatSignupForm(model, onBack = {})
                } else Text("已登录 ${signedIn.id}")
            }
        }
        try {
            compose.setContent(content)
            compose.onNodeWithText("中国大陆手机号").performTextInput("13900000000")
            compose.onNodeWithText("发送短信验证码").performClick()
            awaitText("短信验证码")
            compose.onNodeWithText("短信验证码").performTextInput("123456")
            compose.onNodeWithText("验证手机号").performClick()
            awaitText("创建账号并授权微信")
            assertEquals(0, registrations.get())
            compose.onNodeWithText("创建账号并授权微信").performClick()
            awaitText("等待微信授权…")
            recreate(content)
            awaitText("等待微信授权…")
            compose.runOnIdle {
                authorization.handle(Intent())
                authorization.handle(Intent())
            }
            awaitText("已登录 $uid")

            val secret = requireNotNull(vault.readSecret(AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON))
            assertEquals(uid, Json.parseToJsonElement(secret).jsonObject.getValue("subject").jsonPrimitive.content)
            assertTrue(listOf("fixture-proof", "fixture-code", "13900000000").none { it in secret })
            val restored = newSessionManager()
            runBlocking {
                withTimeout(5_000) {
                    restored.awaitInitialHydration()
                    restored.refreshProfileAndEntitlement().getOrThrow()
                }
            }
            assertEquals(uid, restored.state.value.account?.id)
            assertEquals(1, registrations.get())
            assertEquals(1, exchanges.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun signupBackCancelsOnlyItsOwnAuthorizationAndInterruptedCreationOffersAccountRecovery() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CookDatabase::class.java).build()
        val devices = mutableListOf<String>()
        val calls = AtomicInteger()
        val returnsToLogin = AtomicInteger()
        val finishRegistrationRequest = CompletableDeferred<Unit>()
        val vault = object : SecretVault {
            override fun readSecret(key: String): String? = null
            override fun writeSecret(key: String, value: String): Unit = error("Signup recovery must keep the current session signed out")
            override fun deleteSecret(key: String) = Unit
        }
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String,
                url: String,
                headers: Map<String, String>,
                jsonBody: String?,
                maxResponseBytes: Int,
                connectTimeoutMs: Int,
                readTimeoutMs: Int,
            ): HttpResponsePayload {
                assertEquals(YunlefunAccountService.TICKET_URL, url)
                val fields = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                val body = when (fields.getValue("action").jsonPrimitive.content) {
                    "sendCookSignupSms" -> {
                        devices += requireNotNull(headers["x-device-id"])
                        """{"ok":true,"challenge":"fixture-sms"}"""
                    }
                    "verifyCookSignupSms" -> if (devices.size == 1) {
                        """{"ok":true,"next":"confirm","proof":"fixture-proof"}"""
                    } else """{"ok":true,"next":"existing"}"""
                    "registerCookWeChat" -> {
                        assertEquals("fixture-proof", fields.getValue("proof").jsonPrimitive.content)
                        assertEquals("fixture-code", fields.getValue("code").jsonPrimitive.content)
                        calls.incrementAndGet()
                        finishRegistrationRequest.await()
                        throw IOException("Fixture server lost the reply after receiving the request")
                    }
                    else -> error("Unexpected signup recovery request")
                }
                return HttpResponsePayload(200, body)
            }
        }
        val service = YunlefunAccountService(transport)
        val sessions = YunlefunSessionManager(vault, database, service)
        val states = mutableListOf<String>()
        var callbackState: String? = null
        val available = mutableStateOf(true)
        val authorization = WeChatAuthorization(object : WeChatAuthorization.Driver {
            override fun isInstalled() = available.value

            override fun send(state: String): Boolean {
                states += state
                return true
            }

            override fun handle(intent: Intent, onResponse: (ResponseStatus, String?, String?) -> Unit) {
                onResponse(ResponseStatus.AUTHORIZED, callbackState, "fixture-code")
            }
        })
        val otherRequestScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val otherReply = CompletableDeferred<String?>()
        val readyToStartSignup = CompletableDeferred<Unit>()
        try {
            compose.setContent {
                CookTheme {
                    val model = viewModel {
                        WeChatSignupViewModel(service, authorization::authorize) { proof, device, authorize ->
                            readyToStartSignup.await()
                            sessions.registerWithWeChat(proof, device, authorize)
                        }
                    }
                    WeChatSignupForm(
                        model = model,
                        onBack = { returnsToLogin.incrementAndGet() },
                        weChatAvailable = available.value,
                    )
                }
            }
            compose.onNodeWithText("中国大陆手机号").performTextInput("13900000000")
            fun verifyNumber() {
                compose.onNodeWithText("发送短信验证码").performClick()
                awaitText("短信验证码")
                compose.onNodeWithText("短信验证码").performTextInput("123456")
                compose.onNodeWithText("验证手机号").performClick()
            }
            verifyNumber()
            awaitText("创建账号并授权微信")
            compose.runOnIdle {
                otherRequestScope.launch { otherReply.complete(authorization.authorize()) }
                assertTrue(authorization.isAwaiting)
            }
            compose.onNodeWithText("创建账号并授权微信").performClick()
            awaitText("正在处理注册…")
            compose.onNodeWithText("取消等待授权").assertDoesNotExist()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.runOnIdle {
                assertTrue(authorization.isAwaiting)
                assertEquals(0, calls.get())
                callbackState = states.single()
                authorization.handle(Intent())
            }
            compose.waitUntil(5_000) { otherReply.isCompleted }
            assertEquals("fixture-code", runBlocking { otherReply.await() })
            readyToStartSignup.complete(Unit)
            awaitText("取消等待授权")
            val cancelledSignupState = compose.runOnIdle { states.last() }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("创建账号并授权微信").assertIsEnabled()
            assertEquals(0, calls.get())
            assertEquals(0, returnsToLogin.get())

            compose.onNodeWithText("创建账号并授权微信").performClick()
            awaitText("取消等待授权")
            val unavailableSignupState = compose.runOnIdle {
                available.value = false
                authorization.onForeground()
                states.last()
            }
            awaitText("微信授权暂不可用，请稍后重试。")
            compose.onNodeWithText("创建账号并授权微信").assertIsNotEnabled()
            compose.onNodeWithText("手机号已验证。", substring = true).assertExists()
            assertEquals(0, calls.get())
            compose.runOnIdle { available.value = true }

            compose.onNodeWithText("创建账号并授权微信").assertIsEnabled().performClick()
            awaitText("取消等待授权")
            compose.runOnIdle {
                callbackState = cancelledSignupState
                authorization.handle(Intent())
                callbackState = unavailableSignupState
                authorization.handle(Intent())
                assertEquals(0, calls.get())
                callbackState = states.last()
                authorization.handle(Intent())
            }
            compose.waitUntil(5_000) { calls.get() == 1 }
            awaitText("正在处理注册…")
            compose.onNodeWithText("取消等待授权").assertDoesNotExist()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            assertEquals(0, returnsToLogin.get())
            finishRegistrationRequest.complete(Unit)
            awaitText(WeChatAccountException.Reason.SIGNUP_UNCONFIRMED.message)
            compose.onNodeWithText("返回登录").assertIsEnabled()
            assertEquals(1, calls.get())
            assertNull(sessions.state.value.credential)

            compose.onNodeWithText("重新验证手机号").performClick()
            verifyNumber()
            awaitText("返回登录原账号")
            assertEquals(2, devices.distinct().size)
            assertEquals(1, calls.get())
            compose.onNodeWithText("返回登录原账号").performClick()
            assertEquals(1, returnsToLogin.get())
        } finally {
            finishRegistrationRequest.complete(Unit)
            otherRequestScope.cancel()
            database.close()
        }
    }

    private fun recreate(content: @Composable () -> Unit) {
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(timeoutMillis = 5_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
