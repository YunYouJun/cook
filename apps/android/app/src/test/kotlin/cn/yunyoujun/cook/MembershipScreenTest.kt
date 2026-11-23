package cn.yunyoujun.cook

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.PlayLedger
import cn.yunyoujun.cook.data.remote.PlayMembershipPlan
import cn.yunyoujun.cook.data.remote.SUBSCRIPTION_PRODUCT_ID
import cn.yunyoujun.cook.data.remote.PlayMembershipService
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunAccountWebHandoff
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.PlayBillingUpdate
import cn.yunyoujun.cook.platform.PlayCheckoutId
import cn.yunyoujun.cook.platform.PlayBillingUnavailable
import cn.yunyoujun.cook.platform.PlayCheckoutResult
import cn.yunyoujun.cook.platform.PlayMembershipBilling
import cn.yunyoujun.cook.platform.PlayMembershipOffer
import cn.yunyoujun.cook.platform.PlayMembershipPurchase
import cn.yunyoujun.cook.platform.PlayMembershipStore
import cn.yunyoujun.cook.ui.screens.MembershipScreen
import cn.yunyoujun.cook.ui.state.AccountBrowserFlow
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MembershipScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun membershipExpiresWhileThePageStaysOpen() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val initialTime = ZonedDateTime.now()
        val clock = AtomicReference(initialTime)
        val expiry = initialTime.plusMinutes(2).toInstant().toEpochMilli()
        val requests = AtomicInteger()
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String, url: String, headers: Map<String, String>, jsonBody: String?,
                maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
            ): HttpResponsePayload {
                assertEquals(MembershipService.FUNCTIONS_GATEWAY + "account-api", url)
                requests.incrementAndGet()
                return HttpResponsePayload(200, """{"membership":{"isActive":true,"expireAt":$expiry}}""")
            }
        }
        val sessions = createSessions(database, transport)
        try {
            compose.setContent {
                CookTheme {
                    MembershipScreen(
                        sessions, viewModel(), onBack = {}, onOpenAccount = {}, onOpenPrivacy = {}, onOpenTerms = {},
                        readLocalTime = clock::get,
                    )
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                sessions.state.value.entitlement?.expiresAtEpochMs == expiry && !sessions.state.value.isCheckingMembership
            }
            compose.onNodeWithText("会员有效期至", substring = true).assertIsDisplayed()
            val visionBenefit = compose.onNodeWithText("冰箱批量识图")
            if (BuildConfig.COOK_FRIDGE_VISION_ENABLED) visionBenefit.assertExists() else visionBenefit.assertDoesNotExist()

            compose.runOnIdle {
                clock.set(initialTime.plusMinutes(3))
                app.sendBroadcast(Intent(Intent.ACTION_TIME_TICK))
            }
            shadowOf(Looper.getMainLooper()).idle()
            compose.onNodeWithText("尚未开通会员或会员已到期").assertIsDisplayed()
            assertEquals(1, requests.get())
        } finally {
            database.close()
        }
    }

    @Test
    fun checkExistingPurchaseOnEveryBuildAndOpenTheAccountHandoffOnDirectBuild() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).setInstallSourceInfo(app.packageName, "com.android.shell", "com.android.shell")
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val expiry = AtomicLong()
        val blockRestore = AtomicBoolean(false)
        val restoreStarted = CompletableDeferred<Unit>()
        val finishRestore = CompletableDeferred<Unit>()
        val showMembership = mutableStateOf(true)
        val approvals = AtomicInteger()
        val openedHandoff = AtomicReference<String?>()
        val openedCheckout = AtomicReference<String?>()
        val failFirstCheckoutOpen = AtomicBoolean(true)
        val browserError = AtomicReference<String?>()
        val binding = "b".repeat(43)
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String, url: String, headers: Map<String, String>, jsonBody: String?,
                maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
            ): HttpResponsePayload = when (url) {
                YunlefunAccountWebHandoff.ENDPOINT -> HttpResponsePayload(200, """{"supported":true,"systemBrowser":true}""")
                "${MembershipService.FUNCTIONS_GATEWAY}account-api" -> {
                    val value = expiry.get()
                    if (blockRestore.get()) {
                        restoreStarted.complete(Unit)
                        finishRestore.await()
                    }
                    HttpResponsePayload(200, """{"membership":{"isActive":${value > 0},"expireAt":$value}}""")
                }
                YunlefunAccountService.TICKET_URL -> {
                    val fields = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                    assertEquals("approveCookWebHandoff", fields.getValue("action").jsonPrimitive.content)
                    assertEquals(binding, fields.getValue("binding").jsonPrimitive.content)
                    approvals.incrementAndGet()
                    HttpResponsePayload(200, """{"ok":true}""")
                }
                else -> error("Unexpected URL $url")
            }
        }
        val sessions = createSessions(database, transport)
        try {
            val activity = compose.activity
            val looper = shadowOf(Looper.getMainLooper())
            val browser = ViewModelProvider(activity)[AccountBrowserFlow::class.java]
            activity.lifecycleScope.launch {
                activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    browser.deliver(sessions, open = { url ->
                        if (url.startsWith(YunlefunAccountWebHandoff.ENDPOINT)) {
                            openedHandoff.set(url)
                        } else {
                            if (failFirstCheckoutOpen.getAndSet(false)) throw ActivityNotFoundException()
                            openedCheckout.set(url)
                        }
                    }, showMessage = { browserError.set(it) })
                }
            }
            compose.setContent {
                CookTheme {
                    if (showMembership.value) {
                        MembershipScreen(
                            sessions, browser, onBack = { showMembership.value = false },
                            onOpenAccount = {}, onOpenPrivacy = {}, onOpenTerms = {},
                        )
                    }
                }
            }
            compose.waitUntil(timeoutMillis = 5_000) {
                sessions.state.value.entitlement != null && !sessions.state.value.isCheckingMembership
            }
            compose.onNodeWithText("尚未开通会员或会员已到期").assertIsDisplayed()
            if (BuildConfig.COOK_WEB_MEMBERSHIP_CHECKOUT) {
                compose.onNodeWithText("前往购买会员").assertIsEnabled().performClick()
                compose.waitUntil(timeoutMillis = 5_000) {
                    looper.idle()
                    openedHandoff.get() != null || browserError.get() != null
                }
                assertNull(browserError.get())
                compose.runOnIdle {
                    ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(YunlefunAccountWebHandoff.LIFETIME_MS))
                    sessions.refreshAccountWebsiteOnForeground()
                }
                compose.waitUntil(timeoutMillis = 5_000) { sessions.state.value.expiredWebsitePurpose != null }
                compose.onNodeWithText("购买授权已过期，请重新打开购买入口。").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("粘贴并继续").assertDoesNotExist()
                assertEquals(0, approvals.get())
                openedHandoff.set(null)
                compose.onNodeWithText("前往购买会员").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(timeoutMillis = 5_000) {
                    looper.idle()
                    openedHandoff.get() != null || browserError.get() != null
                }
                assertNull(browserError.get())
                compose.onNodeWithText("购买授权已过期，请重新打开购买入口。").assertDoesNotExist()
                val handoff = Uri.parse(requireNotNull(openedHandoff.get()))
                assertEquals("www.yunle.fun", handoff.host)
                assertEquals("/api/session/cook-handoff", handoff.path)
                assertEquals("browser", handoff.getQueryParameter("transport"))

                val paste = compose.onNodeWithText("粘贴并继续")
                val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
                fun setClipboard(link: String) = compose.runOnIdle {
                    clipboard.setPrimaryClip(ClipData.newPlainText("Callback fixture", link))
                }
                setClipboard("${YunlefunSSO.REDIRECT_URI}#ylf_sso=wrong_callback_type")
                paste.performScrollTo().performClick()
                compose.onNodeWithText("剪贴板里没有对应的回调链接", substring = true).assertExists()
                assertEquals(0, approvals.get())
                assertTrue(sessions.state.value.pendingWebsitePurpose != null)

                val request = requireNotNull(handoff.getQueryParameter("request"))
                setClipboard("  ${YunlefunSSO.REDIRECT_URI}#cook_handoff=$request.$binding\n")
                paste.performScrollTo().assertIsEnabled().performClick()
                compose.onNodeWithText("剪贴板里没有对应的回调链接", substring = true).assertDoesNotExist()
                compose.waitUntil(timeoutMillis = 5_000) {
                    looper.idle()
                    browserError.get() != null
                }
                assertEquals("未找到可用的系统浏览器。", browserError.getAndSet(null))
                assertFalse(sessions.state.value.awaitingMembershipReturn)
                assertEquals(1, approvals.get())
                paste.assertDoesNotExist()
                compose.onNodeWithText("继续打开结账页").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(timeoutMillis = 5_000) {
                    looper.idle()
                    openedCheckout.get() != null || browserError.get() != null
                }
                assertNull(browserError.get())
                assertEquals("${YunlefunSSO.ISSUER}/auth/cook-account?request=$request&binding=$binding#membership", openedCheckout.get())
                assertEquals(1, approvals.get())
                assertTrue(sessions.state.value.awaitingMembershipReturn)
            } else {
                compose.onNodeWithText("前往购买会员").assertDoesNotExist()
            }
            val newExpiry = System.currentTimeMillis() + 86_400_000L
            expiry.set(newExpiry)
            compose.onNodeWithText("核对已购会员").performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                sessions.state.value.entitlement?.expiresAtEpochMs == newExpiry &&
                    !sessions.state.value.isCheckingMembership
            }
            compose.onNodeWithText("当前账号的会员权益已核对。").assertIsDisplayed()

            val renewedExpiry = newExpiry + 86_400_000L
            expiry.set(renewedExpiry)
            blockRestore.set(true)
            compose.onNodeWithText("核对已购会员").performClick()
            compose.waitUntil(timeoutMillis = 5_000) { restoreStarted.isCompleted }
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("云乐坊会员").assertDoesNotExist()
            finishRestore.complete(Unit)
            compose.waitUntil(timeoutMillis = 5_000) {
                sessions.state.value.entitlement?.expiresAtEpochMs == renewedExpiry &&
                    !sessions.state.value.isCheckingMembership
            }
        } finally {
            finishRestore.complete(Unit)
            database.close()
        }
    }

    @Test
    fun googlePlayMembershipCardShowsStorePriceAndPassesThePlanToNativeCheckout() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val nativeActivity = compose.activity
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val looper = shadowOf(Looper.getMainLooper())
        fun awaitRestore(condition: () -> Boolean) = compose.waitUntil(timeoutMillis = 5_000) {
            looper.idle()
            condition()
        }
        val marker = "1".repeat(64)
        val offer = PlayMembershipOffer(PlayMembershipPlan.MONTH, "¥12.00", 12_000_000, "CNY")
        val monthly = PlayMembershipOffer(PlayMembershipPlan.MONTHLY_SUBSCRIPTION, "¥10.00", 10_000_000, "CNY")
        val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥100.00", 100_000_000, "CNY")
        val launched = AtomicReference<String?>()
        val launchedReplacement = AtomicReference<String?>()
        val launchResult = AtomicReference(PlayCheckoutResult.CANCELLED)
        val capabilitiesUnavailable = AtomicBoolean(true)
        val playQueryFails = AtomicBoolean(true)
        val subscriptionOffersFail = AtomicBoolean(true)
        val pass = AtomicReference<PlayMembershipPurchase?>()
        val subscription = AtomicReference<PlayMembershipPurchase?>()
        val subscriptionToken = "fixture-owned-play-subscription-receipt"
        val subscriptionReference = MessageDigest.getInstance("SHA-256").digest(subscriptionToken.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val expiry = AtomicLong(0L)
        val blockVerification = AtomicBoolean(false)
        val verificationStarted = CompletableDeferred<Unit>()
        val finishVerification = CompletableDeferred<Unit>()
        val showMembership = mutableStateOf(true)
        val transport = object : HttpTransport {
            override suspend fun request(
                method: String, url: String, headers: Map<String, String>, jsonBody: String?,
                maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
            ): HttpResponsePayload = when (url) {
                MembershipService.FUNCTIONS_GATEWAY + PlayLedger.PRODUCTION.endpoint -> {
                    val request = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                    val action = request["action"]?.jsonPrimitive?.content
                    val unavailable = action == "verifyPurchase" || (action == "getCapabilities" && capabilitiesUnavailable.get())
                    val payload = when (action) {
                        "getCapabilities" -> """
                            "products":[{"id":"cook_member_month","cycle":"month","purchaseOptionId":"buy"}],
                            "subscriptions":[
                                {"id":"$SUBSCRIPTION_PRODUCT_ID","cycle":"month","basePlanId":"monthly"},
                                {"id":"$SUBSCRIPTION_PRODUCT_ID","cycle":"year","basePlanId":"yearly"}
                            ]
                        """.trimIndent()
                        "preparePurchase" -> if (request["replacesPurchaseToken"]?.jsonPrimitive?.content == subscriptionToken) {
                            """"productId":"$SUBSCRIPTION_PRODUCT_ID","basePlanId":"yearly","replacementMode":"WITHOUT_PRORATION""""
                        } else {
                            """"productId":"cook_member_month""""
                        }
                        "verifyPurchase" -> """"code":"play_unavailable""""
                        "verifySubscription" -> {
                            if (blockVerification.get()) {
                                verificationStarted.complete(Unit)
                                finishVerification.await()
                            }
                            """"productId":"$SUBSCRIPTION_PRODUCT_ID","basePlanId":"monthly","cycle":"month","state":"active","status":"confirmed","purchaseReference":"$subscriptionReference","autoRenewing":true,"periodExpiresAt":${expiry.get()}"""
                        }
                        else -> error("Unexpected Play request")
                    }
                    HttpResponsePayload(
                        if (unavailable) 503 else 200,
                        """{"application":"cook","packageName":"fun.yunle.cook","ledgerEnvironment":"Production","accountMarker":"$marker",$payload}""",
                    )
                }
                MembershipService.FUNCTIONS_GATEWAY + "account-api" -> {
                    val value = expiry.get()
                    HttpResponsePayload(200, """{"membership":{"isActive":${value > 0},"expireAt":$value}}""")
                }
                YunlefunAccountService.GATEWAY + "user/signout" -> HttpResponsePayload(200, "{}")
                else -> error("Unexpected membership request")
            }
        }
        val billing = object : PlayMembershipBilling {
            override val updates = MutableSharedFlow<PlayBillingUpdate>()
            override suspend fun offers(plans: Set<PlayMembershipPlan>): List<PlayMembershipOffer> {
                if (subscriptionOffersFail.get() && plans.any { it.basePlanId != null }) throw PlayBillingUnavailable()
                return listOf(offer, monthly, yearly).filter { it.plan in plans }
            }
            override suspend fun purchases(): List<PlayMembershipPurchase> {
                if (playQueryFails.get()) throw PlayBillingUnavailable()
                return listOfNotNull(pass.get())
            }
            override suspend fun subscriptionPurchases() = listOfNotNull(subscription.get())
            override suspend fun launch(
                activity: ComponentActivity,
                offer: PlayMembershipOffer,
                accountMarker: String,
                replacesPurchaseToken: String?,
                beginCheckout: () -> PlayCheckoutId?,
            ): PlayCheckoutResult {
                assertEquals(nativeActivity, activity)
                assertTrue(beginCheckout() != null)
                if (replacesPurchaseToken == null) assertEquals(PlayMembershipPlan.MONTH, offer.plan)
                else assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, offer.plan)
                launched.set(accountMarker)
                launchedReplacement.set(replacesPurchaseToken)
                return launchResult.get()
            }
        }
        val sessions = createSessions(database, transport)
        val play = PlayMembershipStore(sessions, PlayMembershipService(PlayLedger.PRODUCTION, transport), billing, scope)
        try {
            compose.setContent {
                CookTheme {
                    if (showMembership.value) {
                        MembershipScreen(
                            sessions, viewModel(), onBack = { showMembership.value = false },
                            onOpenAccount = {}, onOpenPrivacy = {}, onOpenTerms = {}, playMembership = play,
                        )
                    }
                }
            }
            awaitRestore {
                play.state.value.checkoutMessage == "暂时无法连接 Google Play 会员，请重试。" && !play.state.value.busy
            }
            compose.onNodeWithText("管理 Google Play 订阅").performScrollTo().performClick()
            assertEquals("https://play.google.com/store/account/subscriptions", shadowOf(compose.activity).nextStartedActivity.dataString)
            capabilitiesUnavailable.set(false)
            compose.onNodeWithText("重新加载商品").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { play.state.value.offers.isNotEmpty() && !play.state.value.busy }
            compose.onNodeWithText("部分 Google Play 商品暂时无法加载。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("自动续费月付 · ¥10.00").assertDoesNotExist()
            subscriptionOffersFail.set(false)
            compose.onNodeWithText("重新加载商品").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { play.state.value.offers.size == 3 && !play.state.value.busy }
            compose.onNodeWithText("重新加载商品").assertDoesNotExist()
            compose.onNodeWithText("部分 Google Play 订单尚未核对，请恢复购买后重试。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("自动续费月付 · ¥10.00").performScrollTo().assertIsEnabled()
            playQueryFails.set(false)
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().assertIsEnabled().performClick()
            awaitRestore { play.state.value.canPurchase(offer.plan) && !play.state.value.busy }
            compose.onNodeWithText("自动续费月付 · ¥10.00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("自动续费年付 · ¥100.00").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("管理 Google Play 订阅").performScrollTo().performClick()
            val management = shadowOf(compose.activity).nextStartedActivity
            assertEquals("https://play.google.com/store/account/subscriptions", management.dataString)
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { launched.get() == marker && !play.state.value.busy }
            compose.onNodeWithText("已取消购买。").performScrollTo().assertIsDisplayed()

            launchResult.set(PlayCheckoutResult.STARTED)
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { play.state.value.awaitingCheckout && !play.state.value.busy }
            compose.onNodeWithText("本次付款结果待核对。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().assertIsNotEnabled()
            playQueryFails.set(true)
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().assertIsEnabled().performClick()
            awaitRestore {
                play.state.value.awaitingCheckout && !play.state.value.busy &&
                    play.state.value.orderMessage?.contains("暂时无法核对") == true
            }
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().assertIsEnabled()

            launchResult.set(PlayCheckoutResult.CANCELLED)
            playQueryFails.set(false)
            val pendingPass = PlayMembershipPurchase(offer.plan.productId, marker, "fixture-pending-play-pass", pending = true)
            pass.set(pendingPass)
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().performClick()
            awaitRestore { play.state.value.hasPendingPayment && !play.state.value.busy }
            compose.onNodeWithText("Google Play 正在等待付款", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("本次付款结果待核对。").assertDoesNotExist()
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("自动续费月付 · ¥10.00").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().assertIsEnabled()

            pass.set(PlayMembershipPurchase(pendingPass.productId, marker, pendingPass.token, pending = false))
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().performClick()
            awaitRestore { play.state.value.hasPendingVerification && !play.state.value.busy }
            compose.onNodeWithText("已购订单待核对，请恢复 Google Play 购买。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("单次月付 · ¥12.00").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("自动续费月付 · ¥10.00").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().assertIsEnabled()

            pass.set(null)
            val newExpiry = System.currentTimeMillis() + 86_400_000L
            expiry.set(newExpiry)
            subscription.set(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, subscriptionToken, false))
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().performClick()
            awaitRestore {
                sessions.state.value.entitlement?.expiresAtEpochMs == newExpiry && play.state.value.canStartCheckout &&
                    play.state.value.canChangePlan && !play.state.value.busy
            }
            compose.onNodeWithText("当前账号的会员权益已核对。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("管理 Google Play 订阅").performScrollTo().performClick()
            assertEquals(
                "https://play.google.com/store/account/subscriptions?sku=$SUBSCRIPTION_PRODUCT_ID&package=${PlayLedger.PACKAGE}",
                shadowOf(compose.activity).nextStartedActivity.dataString,
            )
            compose.onNodeWithText("下次 Play 扣款将按新方案。已付会员时长继续有效。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("切换为自动续费月付 · ¥10.00").assertDoesNotExist()
            compose.onNodeWithText("切换为自动续费年付 · ¥100.00").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 5_000) { launchedReplacement.get() == subscriptionToken && !play.state.value.busy }

            val renewedExpiry = newExpiry + 86_400_000L
            expiry.set(renewedExpiry)
            blockVerification.set(true)
            compose.onNodeWithText("恢复 Google Play 购买").performScrollTo().performClick()
            awaitRestore { verificationStarted.isCompleted }
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("云乐坊会员").assertDoesNotExist()
            finishVerification.complete(Unit)
            awaitRestore {
                sessions.state.value.entitlement?.expiresAtEpochMs == renewedExpiry &&
                    play.state.value.canChangePlan && !play.state.value.busy
            }

            runBlocking { sessions.signOut() }
            compose.runOnIdle { showMembership.value = true }
            compose.onNodeWithText("登录云乐坊账号").assertIsDisplayed()
            compose.onNodeWithText("管理 Google Play 订阅").performScrollTo().performClick()
            assertEquals("https://play.google.com/store/account/subscriptions", shadowOf(compose.activity).nextStartedActivity.dataString)
        } finally {
            finishVerification.complete(Unit)
            scope.cancel()
            database.close()
        }
    }

    private fun createSessions(database: CookDatabase, transport: HttpTransport): YunlefunSessionManager {
        val credential = YunlefunCredential("access", "refresh", "user", Long.MAX_VALUE, "device")
        val vault = object : SecretVault {
            override fun readSecret(key: String): String? =
                Json.encodeToString(credential).takeIf { key == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON }
            override fun writeSecret(key: String, value: String) = Unit
            override fun deleteSecret(key: String) = Unit
        }
        return YunlefunSessionManager(
            vault, database, YunlefunAccountService(transport), membershipService = MembershipService(transport),
        )
    }
}
