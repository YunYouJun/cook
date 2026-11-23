package cn.yunyoujun.cook

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.local.CookDatabase
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.HttpResponsePayload
import cn.yunyoujun.cook.data.remote.HttpTransport
import cn.yunyoujun.cook.data.remote.MembershipCheckReason
import cn.yunyoujun.cook.data.remote.MembershipService
import cn.yunyoujun.cook.data.remote.MembershipSession
import cn.yunyoujun.cook.data.remote.PlayLedger
import cn.yunyoujun.cook.data.remote.PlayMembershipPlan
import cn.yunyoujun.cook.data.remote.PlaySubscriptionState
import cn.yunyoujun.cook.data.remote.SUBSCRIPTION_PRODUCT_ID
import cn.yunyoujun.cook.data.remote.PlayMembershipService
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunCredential
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.PlayBillingUnavailable
import cn.yunyoujun.cook.platform.PlayBillingUpdate
import cn.yunyoujun.cook.platform.PlayCheckoutId
import cn.yunyoujun.cook.platform.PlayCheckoutResult
import cn.yunyoujun.cook.platform.PlayMembershipBilling
import cn.yunyoujun.cook.platform.PlayMembershipOffer
import cn.yunyoujun.cook.platform.PlayMembershipPurchase
import cn.yunyoujun.cook.platform.PlayMembershipStore
import cn.yunyoujun.cook.platform.acceptsInstallation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayMembershipTest {
    @Test
    fun onlyExplicitSandboxBuildsCanOfferPlayBillingAfterSideloading() {
        for (ledger in PlayLedger.entries) {
            for (licenseTestSideload in listOf(false, true)) {
                assertTrue(ledger.acceptsInstallation(PlayLedger.PACKAGE, true, licenseTestSideload))
                assertFalse(ledger.acceptsInstallation("${PlayLedger.PACKAGE}.debug", true, licenseTestSideload))
                assertFalse(ledger.acceptsInstallation("${PlayLedger.PACKAGE}.debug", false, licenseTestSideload))
                assertFalse(ledger.acceptsInstallation(PlayLedger.PACKAGE, null, licenseTestSideload))
            }
            assertFalse(ledger.acceptsInstallation(PlayLedger.PACKAGE, false, false))
        }
        assertFalse(PlayLedger.PRODUCTION.acceptsInstallation(PlayLedger.PACKAGE, false, true))
        assertTrue(PlayLedger.SANDBOX.acceptsInstallation(PlayLedger.PACKAGE, false, true))
    }

    @Test
    fun checkoutUsesTheCurrentAccountAndCompletesOnlyAfterTheProviderFinishesPayment() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                assertEquals(listOf(offer), fixture.store.state.value.offers)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(marker, fixture.billing.launchMarker)
                assertEquals(1, fixture.transport.preparedPurchases)
                assertTrue(fixture.store.state.value.awaitingCheckout)
                assertTrue(fixture.transport.submitted.isEmpty())

                fixture.billing.emit(PlayCheckoutResult.CANCELLED)
                fixture.waitFor("已取消购买。")
                assertFalse(fixture.store.state.value.awaitingCheckout)

                fixture.billing.emit(PlayCheckoutResult.STARTED, purchase(pending = true))
                assertFalse(fixture.waitFor("Google Play 正在等待付款").canStartCheckout)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.transport.preparedPurchases)
                assertTrue(fixture.transport.submitted.isEmpty())
                assertFalse(fixture.sessions.state.value.entitlement?.isActiveAt() == true)

                // No checkout screen is needed for a delayed purchase update.
                fixture.transport.expiry = expiry
                fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
                assertTrue(fixture.waitFor("Google Play 订单已确认。").canStartCheckout)
                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
                assertEquals(setOf("action", "purchaseToken"), fixture.transport.lastPurchaseBody?.keys)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun checkoutFindsAnOlderPendingPassBeforeOpeningPaymentAndRestoresItOncePaid() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                assertTrue(fixture.store.state.value.canPurchase(offer.plan))
                // Play learns about the old order after this screen's inventory check.
                fixture.billing.owned = listOf(purchase(pending = true))
                fixture.store.purchase(fixture.identity, activity.get(), offer)

                assertEquals(0, fixture.billing.launches)
                assertTrue(fixture.store.state.value.hasPendingPayment)
                assertFalse(fixture.store.state.value.canPurchase(offer.plan))
                assertTrue(fixture.transport.submitted.isEmpty())
                assertFalse(fixture.sessions.state.value.entitlement?.isActiveAt() == true)

                fixture.billing.owned = listOf(purchase())
                fixture.transport.expiry = expiry
                fixture.store.restore(fixture.identity)
                fixture.waitFor("Google Play 订单已确认。")
                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
                assertEquals(0, fixture.billing.launches)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun checkoutRecoversAnOlderPaidPassWithoutLaunchingAnotherPaymentForTheSameTap() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.billing.owned = listOf(purchase())
                fixture.transport.expiry = expiry

                fixture.store.purchase(fixture.identity, activity.get(), offer)

                assertEquals(0, fixture.billing.launches)
                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
                fixture.waitFor("Google Play 订单已确认。")

                fixture.billing.owned = listOf(purchase(account = "another-account"))
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.billing.launches)
                assertEquals(listOf(token), fixture.transport.submitted)
                assertTrue(fixture.store.state.value.awaitingCheckout)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun checkoutWaitsForAWorkingInventoryEvenWhenTheOfferWasAlreadyLoaded() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                assertTrue(fixture.store.state.value.canPurchase(offer.plan))
                fixture.billing.failQuery = true
                fixture.store.purchase(fixture.identity, activity.get(), offer)

                assertEquals(0, fixture.billing.launches)
                assertTrue(fixture.store.state.value.orderInventoryRetryNeeded)
                assertFalse(fixture.store.state.value.canPurchase(offer.plan))

                fixture.billing.failQuery = false
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.store.state.value.canPurchase(offer.plan))
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.billing.launches)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun checkoutStaysClosedIfTheServerCannotRegisterThePurchaseOwner() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.transport.rejectPreparation = true
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertNull(fixture.billing.launchMarker)
                assertFalse(fixture.store.state.value.awaitingCheckout)
                assertTrue(fixture.store.state.value.message.orEmpty().contains("请重试"))
                assertTrue(fixture.transport.submitted.isEmpty())
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun checkoutRenewsARejectedSessionBeforeOpeningPlayOnce() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.transport.rejectAuthentication = { action, authorization ->
                    if (action == "preparePurchase" && authorization == originalAuthorization) {
                        HttpResponsePayload(200, """{"code":"login_required"}""")
                    } else null
                }

                fixture.store.purchase(fixture.identity, activity.get(), offer)

                assertEquals(1, fixture.transport.refreshes)
                assertEquals(1, fixture.transport.preparedPurchases)
                assertEquals(1, fixture.billing.launches)
                assertEquals(marker, fixture.billing.launchMarker)
                assertEquals(fixture.identity, fixture.sessions.state.value.membershipSession)
                assertTrue(fixture.store.state.value.awaitingCheckout)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun restoreRenewsARejectedSessionAndResubmitsTheSameOrder() = runBlocking {
        withStore { fixture ->
            fixture.billing.owned = listOf(purchase())
            fixture.transport.expiry = expiry
            fixture.transport.rejectAuthentication = { action, authorization ->
                if (action == "verifyPurchase" && authorization == originalAuthorization) {
                    HttpResponsePayload(401, "")
                } else null
            }

            fixture.store.restore(fixture.identity)

            assertEquals(1, fixture.transport.refreshes)
            assertEquals(listOf(token, token), fixture.transport.verificationAttempts)
            assertEquals(listOf(token), fixture.transport.submitted)
            assertEquals(fixture.identity, fixture.sessions.state.value.membershipSession)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            assertTrue(fixture.store.state.value.canStartCheckout)
        }
    }

    @Test
    fun aSecondAuthorizationRejectionKeepsTheOrderPendingWithoutFurtherRetries() = runBlocking {
        withStore(verificationRetryDelaysMs = listOf(100, 100, 100)) { fixture ->
            fixture.billing.owned = listOf(purchase())
            fixture.transport.rejectAuthentication = { action, _ ->
                HttpResponsePayload(401, "").takeIf { action == "verifyPurchase" }
            }

            fixture.store.restore(fixture.identity)

            assertEquals(1, fixture.transport.refreshes)
            assertEquals(listOf(token, token), fixture.transport.verificationAttempts)
            assertTrue(fixture.transport.submitted.isEmpty())
            val pending = fixture.waitFor("登录已过期，请重新登录。")
            assertTrue(pending.hasPendingVerification)
            assertFalse(pending.canStartCheckout)
            delay(350)
            assertEquals(listOf(token, token), fixture.transport.verificationAttempts)
            assertEquals(1, fixture.transport.refreshes)

            fixture.transport.rejectAuthentication = { _, _ -> null }
            fixture.transport.expiry = expiry
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.waitFor("Google Play 订单已确认。").canStartCheckout)
            assertEquals(listOf(token, token, token), fixture.transport.verificationAttempts)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            assertEquals(0, fixture.transport.preparedPurchases)
        }
    }

    @Test
    fun restoringAccountEntitlementsRenewsARejectedSessionWithoutPlayOrders() = runBlocking {
        withStore { fixture ->
            fixture.transport.expiry = expiry
            fixture.transport.rejectAuthentication = { action, authorization ->
                HttpResponsePayload(401, "").takeIf { action == "getAccount" && authorization == originalAuthorization }
            }

            fixture.sessions.checkMembership(MembershipCheckReason.RESTORE, fixture.identity)

            assertEquals(1, fixture.transport.refreshes)
            assertEquals(fixture.identity, fixture.sessions.state.value.membershipSession)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            assertTrue(fixture.transport.verificationAttempts.isEmpty())
        }
    }

    @Test
    fun explicitRestoreChecksTheServerAndSkipsOrdersForAnotherAccount() = runBlocking {
        withStore { fixture ->
            val otherAccount = purchase(pending = true, account = "b".repeat(64), receipt = "other-account-receipt")
            fixture.billing.owned = listOf(otherAccount, purchase(pending = true, receipt = "pending-receipt-12345"))
            fixture.transport.expiry = expiry
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.transport.submitted.isEmpty())
            assertTrue(fixture.store.state.value.message.orEmpty().contains("不属于当前"))
            assertTrue(fixture.store.state.value.message.orEmpty().contains("等待付款"))
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)

            // Already consumed purchases are restored from the cloud account even
            // when the device's Play service is offline.
            fixture.billing.failQuery = true
            fixture.transport.expiry += 86_400_000
            fixture.store.restore(fixture.identity)
            assertEquals(fixture.transport.expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            assertTrue(fixture.store.state.value.message.orEmpty().contains("暂时无法核对 Google Play 订单"))
            assertFalse(fixture.store.state.value.canStartCheckout)

            fixture.billing.failQuery = false
            fixture.billing.owned = listOf(otherAccount)
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.store.state.value.canStartCheckout)
            assertTrue(fixture.transport.submitted.isEmpty())
        }
    }

    @Test
    fun returningToTheAppReconcilesOwnedReceiptsEvenWithoutABillingCallback() = runBlocking {
        withStore { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.billing.owned = listOf(purchase())
            fixture.transport.expiry = expiry
            fixture.transport.replyStatus = "committed"
            fixture.store.onForeground()
            val committed = fixture.waitFor("Google Play 确认待重试")
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            assertEquals(listOf(token), fixture.transport.submitted)
            assertFalse(committed.canPurchase(offer.plan))
            assertTrue(committed.canPurchase(PlayMembershipPlan.MONTHLY_SUBSCRIPTION))
            assertFalse(committed.hasPendingVerification)

            fixture.billing.failQuery = true
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.store.state.value.message.orEmpty().contains("Google Play 确认待重试"))
            fixture.billing.failQuery = false
            fixture.transport.verificationFailure = HttpResponsePayload(503, "")
            fixture.store.restore(fixture.identity)
            val retry = fixture.waitFor("Google Play 确认待重试")
            assertFalse(retry.canPurchase(offer.plan))
            assertTrue(retry.canPurchase(PlayMembershipPlan.MONTHLY_SUBSCRIPTION))

            fixture.transport.verificationFailure = null
            fixture.transport.replyStatus = "confirmed"
            fixture.store.restore(fixture.identity)
            val confirmed = fixture.waitFor("Google Play 订单已确认。")
            assertTrue(confirmed.canPurchase(offer.plan))
            assertTrue(confirmed.confirmationPendingProducts.isEmpty())
            assertEquals(listOf(token, token, token), fixture.transport.submitted)
        }
    }

    @Test
    fun restoredNetworkReconcilesAPaidOrderWithoutLeavingTheAppOrBuyingAgain() = runBlocking {
        withStore { fixture ->
            fixture.store.loadOffers(fixture.identity)
            fixture.transport.verificationFailure = HttpResponsePayload(503, "")
            fixture.billing.owned = listOf(purchase())
            fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
            val waiting = fixture.waitFor("已购订单待核对")
            assertFalse(waiting.canPurchase(offer.plan))
            assertFalse(fixture.sessions.state.value.entitlement?.isActiveAt() == true)

            fixture.transport.expiry = expiry
            fixture.transport.verificationFailure = null
            fixture.networkChanges.emit(Unit)

            val recovered = fixture.waitFor("Google Play 订单已确认。")
            assertTrue(recovered.canPurchase(offer.plan))
            assertFalse(recovered.hasPendingVerification)
            assertEquals(listOf(token, token), fixture.transport.submitted)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            assertEquals(0, fixture.transport.preparedPurchases)

            fixture.sessions.signOut()
            withTimeout(5_000) {
                fixture.networkChanges.subscriptionCount.first { it == 0 }
                fixture.store.state.first { it.session == null }
            }
        }
    }

    @Test
    fun foregroundRetriesServiceUnavailableAndConfirmationWhileTheNetworkStaysConnected() = runBlocking {
        withStore(verificationRetryDelaysMs = listOf(250, 250, 250)) { fixture ->
            fixture.store.loadOffers(fixture.identity)
            fixture.transport.verificationFailure = HttpResponsePayload(200, """{"code":"play_unavailable"}""")
            fixture.billing.owned = listOf(purchase())
            fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
            assertFalse(fixture.waitFor("已购订单待核对").canPurchase(offer.plan))

            fixture.transport.verificationFailure = null
            fixture.transport.replyStatus = "committed"
            fixture.transport.expiry = expiry
            assertFalse(fixture.waitFor("Google Play 确认待重试").canPurchase(offer.plan))
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)

            fixture.transport.replyStatus = "confirmed"
            assertTrue(fixture.waitFor("Google Play 订单已确认。").canPurchase(offer.plan))
            delay(300)
            assertEquals(listOf(token, token, token), fixture.transport.verificationAttempts)
            assertEquals(0, fixture.transport.preparedPurchases)
        }
    }

    @Test
    fun orderRejectionsInSuccessfulHttpResponsesWaitForManualRecovery() = runBlocking {
        for (code in listOf("play_not_configured", "purchase_account_mismatch")) {
            withStore(verificationRetryDelaysMs = listOf(100, 100)) { fixture ->
                fixture.transport.verificationFailure = HttpResponsePayload(200, """{"code":"$code"}""")
                fixture.billing.owned = listOf(purchase())
                fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
                val waiting = fixture.waitFor("已购订单待核对")
                assertFalse(waiting.needsOrderRecovery)
                delay(250)
                assertEquals(listOf(token), fixture.transport.verificationAttempts)
                assertFalse(fixture.sessions.state.value.entitlement?.isActiveAt() == true)
            }
        }
    }

    @Test
    fun coldStartRetriesEntitlementWithoutResubmittingTheConfirmedPlayOrder() = runBlocking {
        withStore(
            initialOwned = listOf(purchase()),
            initialEntitlementFailure = 503,
            verificationRetryDelaysMs = listOf(250, 250, 250),
        ) { fixture ->
            fixture.waitFor("Google Play 订单已确认。")
            assertEquals("暂时无法核对会员权益，请重试。", fixture.sessions.state.value.membershipMessage)
            assertNull(fixture.sessions.state.value.entitlement)
            val ownedQueries = fixture.billing.ownedQueries

            fixture.billing.owned = emptyList()
            fixture.transport.expiry = expiry
            fixture.transport.entitlementFailure = null
            withTimeout(5_000) {
                fixture.sessions.state.first { it.entitlement?.expiresAtEpochMs == expiry }
                fixture.store.state.first { !it.busy }
            }
            delay(300)
            assertEquals(2, fixture.transport.accountReads)
            assertEquals(ownedQueries, fixture.billing.ownedQueries)
            assertEquals(listOf(token), fixture.transport.verificationAttempts)
            assertEquals(0, fixture.transport.preparedPurchases)
        }
    }

    @Test
    fun sandboxRetriesEntitlementWithoutChangingProductionMembership() = runBlocking {
        withStore(
            ledger = PlayLedger.SANDBOX,
            initialOwned = listOf(purchase()),
            initialEntitlementFailure = 503,
            verificationRetryDelaysMs = listOf(250, 250, 250),
        ) { fixture ->
            fixture.waitFor("暂时无法核对会员有效期。")
            assertNull(fixture.store.state.value.sandboxEntitlement)

            fixture.transport.expiry = expiry
            fixture.transport.entitlementFailure = null
            withTimeout(5_000) {
                fixture.store.state.first { !it.busy && it.sandboxEntitlement?.expiresAtEpochMs == expiry }
            }
            assertFalse(fixture.store.state.value.message.orEmpty().contains("暂时无法核对会员有效期。"))
            assertNull(fixture.sessions.state.value.entitlement)
            assertEquals(0, fixture.transport.accountReads)
            assertEquals(listOf(token), fixture.transport.verificationAttempts)
        }
    }

    @Test
    fun missingEntitlementEndpointDoesNotScheduleAutomaticRetries() = runBlocking {
        withStore(
            initialOwned = listOf(purchase()),
            initialEntitlementFailure = 404,
            verificationRetryDelaysMs = listOf(100, 100, 100),
        ) { fixture ->
            fixture.waitFor("Google Play 订单已确认。")
            delay(350)
            assertEquals(1, fixture.transport.accountReads)
            assertEquals(listOf(token), fixture.transport.verificationAttempts)
            assertNull(fixture.sessions.state.value.entitlement)
        }
    }

    @Test
    fun coldStartRecoversAPaidOrderAfterTransientCapabilityAndInventoryFailures() = runBlocking {
        withStore(
            initialCapabilitiesFailure = 503,
            initialOneTimeQueryFails = true,
            initialOwned = listOf(purchase()),
            verificationRetryDelaysMs = listOf(250, 250, 250),
        ) { fixture ->
            assertEquals(0, fixture.billing.ownedQueries)
            assertTrue(fixture.transport.verificationAttempts.isEmpty())
            fixture.transport.capabilitiesFailure = null
            withTimeout(5_000) {
                fixture.store.state.first { !it.busy && fixture.billing.ownedQueries == 1 }
            }
            assertTrue(fixture.transport.verificationAttempts.isEmpty())
            fixture.billing.failQuery = false
            fixture.transport.expiry = expiry

            assertTrue(fixture.waitFor("Google Play 订单已确认。").canPurchase(offer.plan))
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            delay(300)
            assertEquals(listOf(token), fixture.transport.verificationAttempts)
            assertEquals(2, fixture.billing.ownedQueries)
            assertEquals(0, fixture.transport.preparedPurchases)
        }
    }

    @Test
    fun anUnavailableLedgerEndpointDoesNotRetryInTheForeground() = runBlocking {
        withStore(initialCapabilitiesFailure = 404, verificationRetryDelaysMs = listOf(100, 100, 100)) { fixture ->
            delay(350)
            assertEquals(1, fixture.transport.capabilitiesRequests)
            assertEquals(0, fixture.billing.ownedQueries)
        }
    }

    @Test
    fun paidOrderVerificationRetriesStopInBackgroundAndAfterSignOut() = runBlocking {
        withStore(verificationRetryDelaysMs = listOf(250, 250)) { fixture ->
            fixture.billing.owned = listOf(purchase(pending = true))
            fixture.billing.emit(PlayCheckoutResult.STARTED, purchase(pending = true))
            fixture.waitFor("Google Play 正在等待付款")
            val queriesBeforePayment = fixture.billing.ownedQueries
            delay(300)
            assertEquals(queriesBeforePayment, fixture.billing.ownedQueries)
            assertTrue(fixture.transport.verificationAttempts.isEmpty())

            fixture.billing.owned = listOf(purchase())
            fixture.transport.verificationFailure = HttpResponsePayload(503, "")
            fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
            fixture.waitFor("已购订单待核对")
            fixture.store.onBackground()
            delay(300)
            assertEquals(listOf(token), fixture.transport.verificationAttempts)

            fixture.store.onForeground()
            withTimeout(5_000) {
                fixture.store.state.first { !it.busy && fixture.transport.verificationAttempts.size == 2 }
            }
            fixture.sessions.signOut()
            withTimeout(5_000) { fixture.store.state.first { it.session == null } }
            delay(300)
            assertEquals(listOf(token, token), fixture.transport.verificationAttempts)
            assertEquals(0, fixture.transport.preparedPurchases)
        }
    }

    @Test
    fun anUnknownCheckoutBlocksAnotherPurchaseUntilItsPlayQuerySucceeds() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                val queriesBeforePause = fixture.billing.ownedQueries
                fixture.store.onBackground()
                fixture.networkChanges.emit(Unit)
                yield()
                assertFalse(fixture.store.state.value.busy)
                assertEquals(queriesBeforePause, fixture.billing.ownedQueries)
                assertTrue(fixture.store.state.value.awaitingCheckout)

                fixture.billing.failQuery = true
                fixture.store.onForeground()

                assertTrue(fixture.waitFor("暂时无法核对 Google Play 订单").awaitingCheckout)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.store.state.value.awaitingCheckout)
                assertEquals(1, fixture.transport.preparedPurchases)
                assertTrue(fixture.transport.submitted.isEmpty())

                fixture.billing.failQuery = false
                fixture.billing.owned = listOf(purchase())
                fixture.transport.expiry = expiry
                fixture.store.restore(fixture.identity)

                assertFalse(fixture.store.state.value.awaitingCheckout)
                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun anEmptyOrderLookupKeepsCheckoutBlockedUntilItsReceiptAppears() = runBlocking {
        withStore(verificationRetryDelaysMs = listOf(250), elapsedRealtime = { 100L }) { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                fixture.store.onBackground()
                fixture.billing.failQuery = true
                fixture.store.onForeground()
                assertTrue(fixture.waitFor("暂时无法核对 Google Play 订单").awaitingCheckout)
                fixture.billing.failQuery = false
                val waiting = withTimeout(5_000) {
                    fixture.store.state.first { it.firstMissingCheckoutOrderAtMs != null && !it.busy }
                }
                assertTrue(waiting.awaitingCheckout)
                assertFalse(waiting.canStartCheckout)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.billing.launches)
                assertTrue(fixture.transport.submitted.isEmpty())

                fixture.billing.owned = listOf(purchase())
                fixture.transport.expiry = expiry
                assertFalse(fixture.waitFor("Google Play 订单已确认。").awaitingCheckout)
                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun aRestartRestoresTheCheckoutOnlyForTheOriginalAccountAndClearsItAfterRepeatedEmptyLookups() = runBlocking {
        val vault = CheckoutTestVault()
        withStore(checkoutVault = vault) { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertTrue(fixture.store.state.value.awaitingCheckout)
                assertEquals(1, vault.values.size)
                assertFalse(vault.values.values.single().contains(credential.subject))
            } finally {
                activity.pause().stop().destroy()
            }
        }

        withStore(ledger = PlayLedger.SANDBOX, checkoutVault = vault) { sandbox ->
            assertFalse(sandbox.store.state.value.awaitingCheckout)
        }
        withStore(checkoutVault = vault, initialCredential = credential.copy(subject = "other-cook-member")) { other ->
            assertFalse(other.store.state.value.awaitingCheckout)
        }
        assertEquals(1, vault.values.size)

        var elapsed = 100L
        withStore(checkoutVault = vault, elapsedRealtime = { elapsed }) { restored ->
            assertTrue(restored.store.state.value.awaitingCheckout)
            assertTrue(restored.store.state.value.needsOrderRecovery)
            restored.store.restore(restored.identity)
            assertTrue(restored.store.state.value.awaitingCheckout)
            assertTrue(restored.transport.submitted.isEmpty())

            elapsed += 30_000L
            restored.store.restore(restored.identity)
            assertFalse(restored.waitFor("暂未查到本次订单").awaitingCheckout)
            assertFalse(restored.store.state.value.needsOrderRecovery)
            assertTrue(vault.values.isEmpty())
            assertEquals(0, restored.billing.launches)
        }
    }

    @Test
    fun paidOrderAfterRestartReconcilesTheOriginalCheckout() = runBlocking {
        val vault = CheckoutTestVault()
        withStore(checkoutVault = vault) { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertTrue(fixture.store.state.value.awaitingCheckout)
            } finally {
                activity.pause().stop().destroy()
            }
        }

        withStore(checkoutVault = vault, initialOwned = listOf(purchase())) { restored ->
            assertFalse(restored.store.state.value.awaitingCheckout)
            assertEquals(listOf(token), restored.transport.submitted)
            assertTrue(vault.values.isEmpty())
            assertEquals(0, restored.billing.launches)
        }
    }

    @Test
    fun networkRecoveryDoesNotEndAnOpenCheckoutAndReturningReconcilesThePaidOrder() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertTrue(fixture.store.state.value.awaitingCheckout)
                val ownedQueries = fixture.billing.ownedQueries

                fixture.networkChanges.emit(Unit)
                yield()
                withTimeout(5_000) { fixture.store.state.first { !it.busy } }
                assertTrue(fixture.store.state.value.awaitingCheckout)
                assertEquals(ownedQueries, fixture.billing.ownedQueries)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.billing.launches)

                fixture.billing.owned = listOf(purchase())
                fixture.transport.expiry = expiry
                fixture.store.onBackground()
                fixture.store.onForeground()
                assertFalse(fixture.waitFor("Google Play 订单已确认。").awaitingCheckout)
                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun afterReturningFromPlayAQueryFailureCanRecoverThroughReconnectOrTimedRetry() = runBlocking {
        for (reconnect in listOf(true, false)) {
            val retryDelays = if (reconnect) emptyList() else listOf(200L, 400L)
            withStore(verificationRetryDelaysMs = retryDelays) { fixture ->
                val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                try {
                    fixture.store.loadOffers(fixture.identity)
                    fixture.store.purchase(fixture.identity, activity.get(), offer)
                    fixture.store.onBackground()

                    fixture.billing.failQuery = true
                    fixture.store.onForeground()
                    val waiting = fixture.waitFor("暂时无法核对 Google Play 订单")
                    assertTrue(waiting.awaitingCheckout)
                    assertFalse(waiting.canStartCheckout)
                    val queriesBeforeRecovery = fixture.billing.ownedQueries

                    fixture.billing.owned = listOf(purchase())
                    fixture.transport.expiry = expiry
                    fixture.billing.failQuery = false
                    if (reconnect) fixture.networkChanges.emit(Unit)

                    assertFalse(fixture.waitFor("Google Play 订单已确认。").awaitingCheckout)
                    assertTrue(fixture.billing.ownedQueries > queriesBeforeRecovery)
                    assertEquals(listOf(token), fixture.transport.submitted)
                    assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
                    assertEquals(1, fixture.billing.launches)
                    assertEquals(1, fixture.transport.preparedPurchases)
                } finally {
                    activity.pause().stop().destroy()
                }
            }
        }
    }

    @Test
    fun aQueuedReconciliationCannotEndAPlayCheckoutItDidNotObserve() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val launchReached = CompletableDeferred<Unit>()
            val returnFromLaunch = CompletableDeferred<Unit>()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.billing.beforeStartingLaunch = {
                    launchReached.complete(Unit)
                    returnFromLaunch.await()
                }
                val purchase = async { fixture.store.purchase(fixture.identity, activity.get(), offer) }
                withTimeout(5_000) { launchReached.await() }
                val ownedQueries = fixture.billing.ownedQueries
                fixture.networkChanges.emit(Unit)
                fixture.store.onForeground()
                yield()
                returnFromLaunch.complete(Unit)
                purchase.await()
                yield()

                val state = withTimeout(5_000) { fixture.store.state.first { !it.busy } }
                assertTrue(state.awaitingCheckout)
                assertEquals(ownedQueries, fixture.billing.ownedQueries)
                assertTrue(fixture.transport.submitted.isEmpty())
            } finally {
                returnFromLaunch.complete(Unit)
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun aCancellationDeliveredWhileOpeningPlayEndsThatCheckout() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.billing.beforeReturningLaunch = {
                    fixture.billing.emit(PlayCheckoutResult.CANCELLED)
                    yield()
                }

                fixture.store.purchase(fixture.identity, activity.get(), offer)
                val cancelled = fixture.waitFor("已取消购买。")
                assertFalse(cancelled.awaitingCheckout)
                assertTrue(cancelled.canPurchase(offer.plan))
                assertEquals(1, fixture.billing.launches)
                assertTrue(fixture.transport.submitted.isEmpty())
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun aReceiptDeliveredBeforeOpeningPlayVerifiesWithoutEndingTheNewCheckout() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.billing.beforeStartingLaunch = {
                    fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
                    fixture.billing.emit(PlayCheckoutResult.CANCELLED)
                    yield()
                }

                fixture.store.purchase(fixture.identity, activity.get(), offer)
                fixture.waitFor("Google Play 订单已确认。")
                yield()
                val settled = fixture.store.state.value
                assertEquals(listOf(token), fixture.transport.submitted)
                assertTrue(settled.awaitingCheckout)
                assertFalse(settled.canPurchase(offer.plan))
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.billing.launches)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun restartWithUnavailableOrderQueryRecoversPendingPassBeforeAllowingCheckout() = runBlocking {
        withStore(initialOneTimeQueryFails = true) { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                assertEquals(listOf(offer), fixture.store.state.value.offers)
                assertFalse(fixture.store.state.value.canPurchase(offer.plan))
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(0, fixture.transport.preparedPurchases)
                assertEquals(0, fixture.billing.launches)

                fixture.billing.owned = listOf(purchase(pending = true))
                fixture.billing.failQuery = false
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.store.state.value.hasPendingPayment)
                assertFalse(fixture.store.state.value.canPurchase(offer.plan))
                assertTrue(fixture.transport.submitted.isEmpty())

                fixture.billing.owned = emptyList()
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.store.state.value.canPurchase(offer.plan))
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.transport.preparedPurchases)
                assertEquals(1, fixture.billing.launches)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun purchasedOrderBlocksNewCheckoutUntilServerVerificationResolves() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.store.loadOffers(fixture.identity)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                fixture.transport.replyStatus = "pending"
                fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())

                val pending = fixture.waitFor("已购订单待核对")
                assertFalse(pending.awaitingCheckout)
                assertFalse(pending.hasPendingPayment)
                assertFalse(pending.canStartCheckout)
                assertFalse(fixture.sessions.state.value.entitlement?.isActiveAt() == true)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                assertEquals(1, fixture.transport.preparedPurchases)

                fixture.billing.failQuery = true
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.store.state.value.hasPendingVerification)
                fixture.billing.failQuery = false
                fixture.billing.owned = listOf(purchase())
                fixture.transport.verificationFailure = HttpResponsePayload(503, "")
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.store.state.value.hasPendingVerification)
                assertFalse(fixture.store.state.value.canStartCheckout)

                fixture.transport.verificationFailure = null
                fixture.transport.replyStatus = "confirmed"
                fixture.transport.expiry = expiry
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.waitFor("Google Play 订单已确认。").canStartCheckout)
                assertEquals(listOf(token, token, token), fixture.transport.submitted)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun offerLookupFailureKeepsTheOtherProductTypeAvailableAndCanBeRetried() = runBlocking {
        withStore { fixture ->
            val subscription = PlayMembershipOffer(PlayMembershipPlan.MONTHLY_SUBSCRIPTION, "¥10.00", 10_000_000, "CNY")
            fixture.transport.subscriptionsEnabled = true
            fixture.billing.offered = listOf(offer, subscription)
            fixture.billing.unavailableOfferPlans = setOf(subscription.plan)

            fixture.store.loadOffers(fixture.identity)
            val passesOnly = fixture.store.state.value
            assertEquals(listOf(offer), passesOnly.offers)
            assertTrue(passesOnly.canPurchase(offer.plan))
            assertTrue(passesOnly.offerLoadFailed)
            assertTrue(passesOnly.message.orEmpty().contains("部分 Google Play 商品暂时无法加载"))

            fixture.store.restore(fixture.identity)
            assertTrue(fixture.store.state.value.offerLoadFailed)

            fixture.billing.unavailableOfferPlans = setOf(offer.plan)
            fixture.store.loadOffers(fixture.identity)
            val subscriptionsOnly = fixture.store.state.value
            assertEquals(listOf(subscription), subscriptionsOnly.offers)
            assertTrue(subscriptionsOnly.canPurchase(subscription.plan))
            assertTrue(subscriptionsOnly.offerLoadFailed)

            fixture.billing.unavailableOfferPlans = setOf(offer.plan, subscription.plan)
            fixture.store.loadOffers(fixture.identity)
            assertTrue(fixture.store.state.value.offers.isEmpty())
            assertEquals("暂时无法加载 Google Play 商品。", fixture.store.state.value.message)

            fixture.billing.unavailableOfferPlans = emptySet()
            fixture.store.loadOffers(fixture.identity)
            assertEquals(listOf(offer, subscription), fixture.store.state.value.offers)
            assertFalse(fixture.store.state.value.offerLoadFailed)
            assertNull(fixture.store.state.value.message)
        }
    }

    @Test
    fun failedSubscriptionLookupLeavesOneTimeCheckoutAvailableForAcknowledgedOrders() = runBlocking {
        withStore { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.transport.verificationFailure = HttpResponsePayload(503, "")
            fixture.billing.ownedSubscriptions = listOf(
                PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, pending = false, acknowledged = true),
            )

            fixture.store.restore(fixture.identity)
            val current = fixture.waitFor("有订单待核对")
            assertTrue(current.ownsSubscription)
            assertFalse(current.canChangePlan)
            assertFalse(current.hasPendingVerification)
            assertTrue(current.canStartCheckout)
            assertEquals(listOf(token), fixture.transport.submitted)

            fixture.billing.failSubscriptionQuery = true
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.store.state.value.canPurchase(PlayMembershipPlan.MONTH))
            assertFalse(fixture.store.state.value.canPurchase(PlayMembershipPlan.MONTHLY_SUBSCRIPTION))
        }
    }

    @Test
    fun acknowledgedSubscriptionStatusRecoversAfterATemporaryVerificationFailure() = runBlocking {
        withStore(verificationRetryDelaysMs = listOf(500, 500, 500)) { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.billing.ownedSubscriptions = listOf(
                PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, pending = false, acknowledged = true),
            )
            fixture.transport.verificationFailure = HttpResponsePayload(503, "")

            fixture.store.restore(fixture.identity)
            val unavailable = fixture.waitFor("有订单待核对")
            assertTrue(unavailable.ownsSubscription)
            assertFalse(unavailable.canChangePlan)
            assertFalse(unavailable.hasPendingVerification)
            assertTrue(unavailable.canPurchase(PlayMembershipPlan.MONTH))
            assertEquals(listOf(token), fixture.transport.submitted)

            fixture.transport.expiry = expiry
            fixture.transport.verificationFailure = null
            val recovered = fixture.waitFor("Google Play 订单已确认。")
            assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, recovered.subscription?.plan)
            assertTrue(recovered.canChangePlan)
            assertTrue(recovered.canPurchase(PlayMembershipPlan.MONTH))
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            delay(600)
            assertEquals(listOf(token, token), fixture.transport.submitted)
            assertEquals(0, fixture.transport.preparedPurchases)
        }
    }

    @Test
    fun acknowledgedSubscriptionWithAnUnavailableVerificationEndpointWaitsForManualRecovery() = runBlocking {
        withStore(verificationRetryDelaysMs = listOf(100, 100, 100)) { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.billing.ownedSubscriptions = listOf(
                PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, pending = false, acknowledged = true),
            )
            var verificationRequests = 0
            fixture.transport.rejectAuthentication = { action, _ ->
                if (action == "verifySubscription") {
                    verificationRequests++
                    HttpResponsePayload(404, "")
                } else null
            }

            fixture.store.restore(fixture.identity)
            val unavailable = fixture.waitFor("有订单待核对")
            assertFalse(unavailable.canChangePlan)
            assertTrue(unavailable.canPurchase(PlayMembershipPlan.MONTH))
            delay(350)
            assertEquals(1, verificationRequests)
        }
    }

    @Test
    fun subscriptionLookupFailureDoesNotKeepAOneTimeCheckoutWaiting() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                fixture.transport.subscriptionsEnabled = true
                fixture.store.loadOffers(fixture.identity)
                fixture.store.purchase(fixture.identity, activity.get(), offer)
                fixture.billing.failSubscriptionQuery = true
                fixture.billing.owned = listOf(purchase(pending = true))
                fixture.store.onForeground()

                val current = fixture.waitFor("正在等待付款")
                assertFalse(current.awaitingCheckout)
                assertFalse(current.canStartCheckout)
                assertTrue(fixture.transport.submitted.isEmpty())
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun returningDuringAnOldQueryRechecksForPaymentsCompletedInTheBackground() = runBlocking {
        withStore { fixture ->
            val checkingPendingPayment = CompletableDeferred<Unit>()
            val finishOldQuery = CompletableDeferred<Unit>()
            fixture.billing.owned = listOf(purchase(pending = true))
            fixture.billing.afterReadingOwnedPurchases = {
                checkingPendingPayment.complete(Unit)
                finishOldQuery.await()
            }
            fixture.store.onForeground()
            withTimeout(5_000) { checkingPendingPayment.await() }

            fixture.billing.owned = listOf(purchase())
            fixture.billing.afterReadingOwnedPurchases = {}
            fixture.transport.expiry = expiry
            fixture.store.onForeground()
            fixture.networkChanges.emit(Unit)
            finishOldQuery.complete(Unit)

            fixture.waitFor("Google Play 订单已确认。")
            assertEquals(listOf(token), fixture.transport.submitted)
            assertEquals(3, fixture.billing.ownedQueries)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
        }
    }

    @Test
    fun successfulOwnedQueryClearsPendingNoticeAfterCancelledPaymentDisappears() = runBlocking {
        withStore { fixture ->
            fixture.billing.owned = listOf(purchase(pending = true))
            fixture.store.onForeground()
            assertFalse(fixture.waitFor("正在等待付款").canStartCheckout)

            fixture.billing.owned = emptyList()
            fixture.billing.failQuery = true
            fixture.store.onForeground()
            withTimeout(5_000) { fixture.store.state.first { it.busy } }
            withTimeout(5_000) { fixture.store.state.first { !it.busy } }
            assertTrue(fixture.store.state.value.message.orEmpty().contains("正在等待付款"))
            assertFalse(fixture.store.state.value.canStartCheckout)

            fixture.billing.failQuery = false
            fixture.store.onForeground()
            withTimeout(5_000) { fixture.store.state.first { !it.busy && it.message == null } }
            assertTrue(fixture.store.state.value.canStartCheckout)
            assertTrue(fixture.transport.submitted.isEmpty())
            assertFalse(fixture.sessions.state.value.entitlement?.isActiveAt() == true)
        }
    }

    @Test
    fun confirmingOnePaymentDoesNotClearAnotherPendingOrder() = runBlocking {
        withStore { fixture ->
            val second = purchase(pending = true, receipt = "second-pending-receipt")
            fixture.billing.owned = listOf(purchase(pending = true), second)
            fixture.store.restore(fixture.identity)
            assertFalse(fixture.store.state.value.canStartCheckout)

            fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
            val confirmed = fixture.waitFor("Google Play 订单已确认。")
            assertFalse(confirmed.canStartCheckout)
            assertTrue(confirmed.message.orEmpty().contains("正在等待付款"))
            assertEquals(listOf(token), fixture.transport.submitted)
        }
    }

    @Test
    fun foregroundRefreshSeesServerSettlementAndRefundEvenWhenNoPlayReceiptRemains() = runBlocking {
        withStore { fixture ->
            fixture.transport.expiry = expiry
            fixture.store.onForeground()
            withTimeout(5_000) {
                fixture.sessions.state.first { it.entitlement?.expiresAtEpochMs == expiry }
                fixture.store.state.first { !it.busy }
            }
            fixture.transport.expiry = 0
            fixture.store.onForeground()
            withTimeout(5_000) {
                fixture.sessions.state.first { it.entitlement?.isActive == false }
            }
            assertTrue(fixture.transport.submitted.isEmpty())
        }
    }

    @Test
    fun cancelledOneTimeReceiptRefreshesEntitlementWithoutAnotherForegroundEvent() = runBlocking {
        withStore { fixture ->
            fixture.transport.expiry = expiry
            fixture.store.restore(fixture.identity)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
            val reads = fixture.transport.accountReads

            fixture.transport.replyStatus = "cancelled"
            fixture.transport.expiry = 0
            fixture.billing.emit(PlayCheckoutResult.STARTED, purchase())
            val updated = fixture.waitFor("Google Play 订单已取消。")
            assertFalse(updated.hasPendingVerification)
            assertTrue(updated.canPurchase(offer.plan))
            assertEquals(false, fixture.sessions.state.value.entitlement?.isActive)
            assertEquals(reads + 1, fixture.transport.accountReads)
        }
    }

    @Test
    fun lateSettlementAfterSignOutDoesNotWriteEntitlementOrLeavePurchaseStateVisible() = runBlocking {
        withStore { fixture ->
            val queriesBeforeSignOut = fixture.transport.accountReads
            fixture.billing.owned = listOf(purchase())
            val requested = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.transport.onVerify = {
                requested.complete(Unit)
                release.await()
            }
            val restore = async { fixture.store.restore(fixture.identity) }
            withTimeout(5_000) { requested.await() }
            fixture.sessions.signOut()
            release.complete(Unit)
            withTimeout(5_000) { restore.await() }
            withTimeout(5_000) { fixture.store.state.first { it.session == null } }
            assertNull(fixture.sessions.state.value.entitlement)
            assertNull(fixture.store.state.value.message)
            assertTrue(fixture.store.state.value.offers.isEmpty())
            assertEquals(queriesBeforeSignOut, fixture.transport.accountReads)
        }
    }

    @Test
    fun sandboxReadsOnlyTestEntitlementWithoutGrantingProductionMemberAccess() = runBlocking {
        withStore(PlayLedger.SANDBOX) { fixture ->
            fixture.transport.expiry = expiry
            fixture.billing.owned = listOf(purchase())
            fixture.store.restore(fixture.identity)
            assertEquals(listOf(token), fixture.transport.submitted)
            assertEquals(expiry, fixture.store.state.value.sandboxEntitlement?.expiresAtEpochMs)
            assertNull(fixture.sessions.state.value.entitlement)
            assertEquals(0, fixture.transport.accountReads)
        }
    }

    @Test
    fun recurringPlansUseOneProductAndRestoreOnlyTheVerifiedSubscription() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥120.00", 120_000_000L, "CNY")
                fixture.transport.subscriptionsEnabled = true
                fixture.billing.offered = listOf(offer, yearly)
                fixture.store.loadOffers(fixture.identity)
                assertEquals(listOf(offer, yearly), fixture.store.state.value.offers)
                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                val prepared = fixture.transport.lastPreparation
                assertEquals(SUBSCRIPTION_PRODUCT_ID, prepared?.get("productId")?.jsonPrimitive?.content)
                assertEquals("yearly", prepared?.get("basePlanId")?.jsonPrimitive?.content)
                assertEquals(marker, fixture.billing.launchMarker)

                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, "2".repeat(64), token, false))
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.transport.submitted.isEmpty())
                assertNull(fixture.store.state.value.subscription)

                // The SDK may omit account identifiers for a subscription resumed in Play Store.
                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, null, token, false))
                fixture.billing.failQuery = true
                fixture.transport.expiry = expiry
                fixture.store.restore(fixture.identity)
                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals("verifySubscription", fixture.transport.lastPurchaseBody?.get("action")?.jsonPrimitive?.content)
                val subscription = fixture.store.state.value.subscription
                assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, subscription?.plan)
                assertEquals(PlaySubscriptionState.CANCELLED, subscription?.state)
                assertEquals(false, subscription?.autoRenewing)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)

                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                assertEquals(1, fixture.transport.preparedPurchases)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun changesPlansUsingOnlyTheCurrentOwnedTokenAndExplicitServerApproval() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val monthly = PlayMembershipOffer(PlayMembershipPlan.MONTHLY_SUBSCRIPTION, "¥12.00", 12_000_000L, "CNY")
                val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥120.00", 120_000_000L, "CNY")
                val transport = fixture.transport
                transport.subscriptionsEnabled = true
                transport.recurringPlan = PlayMembershipPlan.MONTHLY_SUBSCRIPTION
                transport.expiry = expiry
                fixture.billing.offered = listOf(monthly, yearly)
                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, false))
                fixture.store.loadOffers(fixture.identity)
                fixture.store.restore(fixture.identity)
                assertTrue(fixture.store.state.value.canChangePlan)

                fixture.store.purchase(fixture.identity, activity.get(), monthly)
                assertEquals(0, transport.preparedPurchases)
                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, "2".repeat(64), token, false))
                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                assertEquals(0, transport.preparedPurchases)
                assertNull(fixture.billing.launchMarker)
                assertNull(fixture.store.state.value.subscription)

                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, false))
                fixture.store.restore(fixture.identity)
                transport.approvesPlanChange = false
                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                assertEquals(1, transport.preparedPurchases)
                assertNull(fixture.billing.launchMarker)

                transport.approvesPlanChange = true
                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                assertEquals(token, transport.lastPreparation?.get("replacesPurchaseToken")?.jsonPrimitive?.content)
                assertEquals("yearly", transport.lastPreparation?.get("basePlanId")?.jsonPrimitive?.content)
                assertEquals(token, fixture.billing.launchedReplacementToken)

                val newToken = "new-play-subscription-receipt-1200"
                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, newToken, false))
                transport.replyToken = newToken
                transport.recurringPlan = PlayMembershipPlan.YEARLY_SUBSCRIPTION
                fixture.store.restore(fixture.identity)
                assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, fixture.store.state.value.subscription?.plan)
                assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)

                fixture.store.purchase(fixture.identity, activity.get(), monthly)
                assertEquals(newToken, fixture.billing.launchedReplacementToken)
                assertEquals("monthly", transport.lastPreparation?.get("basePlanId")?.jsonPrimitive?.content)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun unrelatedSubscriptionUpdatesCannotEndAnInProgressPlanChange() = runBlocking {
        val vault = CheckoutTestVault()
        withStore(checkoutVault = vault) { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥120.00", 120_000_000L, "CNY")
                val transport = fixture.transport
                transport.subscriptionsEnabled = true
                transport.recurringPlan = PlayMembershipPlan.MONTHLY_SUBSCRIPTION
                transport.expiry = expiry
                val original = PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, pending = false, acknowledged = true)
                fixture.billing.offered = listOf(yearly)
                fixture.billing.ownedSubscriptions = listOf(original)
                fixture.store.loadOffers(fixture.identity)
                assertTrue(fixture.store.state.value.canChangePlan)

                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                val changing = fixture.store.state.value
                assertTrue(changing.awaitingCheckout)
                assertEquals(token, fixture.billing.launchedReplacementToken)
                val saved = Json.parseToJsonElement(vault.values.values.single()).jsonObject
                assertEquals(43, saved["replacedTokenDigest"]?.jsonPrimitive?.content?.length)
                assertFalse(vault.values.values.single().contains(token))

                fixture.reportPurchases(
                    PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, "2".repeat(64), "another-account-receipt", pending = false),
                    PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, null, "unmarked-subscription-receipt", pending = false),
                )
                assertEquals(changing, fixture.store.state.value)
                assertEquals(listOf(token), transport.submitted)

                fixture.reportPurchases(original)
                assertTrue(fixture.store.state.value.awaitingCheckout)
                assertEquals(PlayMembershipPlan.MONTHLY_SUBSCRIPTION, fixture.store.state.value.subscription?.plan)
                fixture.store.onBackground()
                fixture.store.onForeground()
                val afterInventory = withTimeout(5_000) {
                    fixture.store.state.first { it.firstMissingCheckoutOrderAtMs != null && !it.busy }
                }
                assertTrue(afterInventory.awaitingCheckout)
                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                assertEquals(1, fixture.billing.launches)

                val replacement = "yearly-replacement-play-receipt"
                transport.replyToken = replacement
                transport.recurringPlan = PlayMembershipPlan.YEARLY_SUBSCRIPTION
                fixture.reportPurchases(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, replacement, pending = false))

                val confirmed = fixture.store.state.value
                assertFalse(confirmed.awaitingCheckout)
                assertTrue(confirmed.canChangePlan)
                assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, confirmed.subscription?.plan)
                assertEquals(listOf(token, token, token, replacement), transport.submitted)
                assertTrue(vault.values.isEmpty())
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun unmarkedSubscriptionUpdateRecoversTheOrderThroughPlayInventory() = runBlocking {
        withStore { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.transport.expiry = expiry
            val legacy = PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, null, token, pending = false, acknowledged = true)
            val firstQueryCount = fixture.billing.ownedQueries

            fixture.reportPurchases(legacy)
            assertNull(fixture.store.state.value.subscription)
            assertTrue(fixture.transport.submitted.isEmpty())

            fixture.billing.ownedSubscriptions = listOf(legacy)
            fixture.reportPurchases(legacy)
            assertEquals(firstQueryCount + 2, fixture.billing.ownedQueries)
            assertEquals(listOf(token), fixture.transport.submitted)
            assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, fixture.store.state.value.subscription?.plan)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
        }
    }

    @Test
    fun restoringAfterSwitchingPlayAccountsClearsTheOldSubscriptionPlan() = runBlocking {
        withStore { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.transport.expiry = expiry
            fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, false))
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.store.state.value.canChangePlan)

            val foreignToken = "other-play-subscription-receipt"
            fixture.billing.ownedSubscriptions =
                listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, "2".repeat(64), foreignToken, false))
            fixture.store.restore(fixture.identity)

            val current = fixture.store.state.value
            assertTrue(current.ownsSubscription)
            assertNull(current.subscription)
            assertFalse(current.canChangePlan)
            assertTrue(current.message.orEmpty().contains("不属于当前云乐坊账号"))
            assertEquals(listOf(token), fixture.transport.submitted)
            assertEquals(expiry, fixture.sessions.state.value.entitlement?.expiresAtEpochMs)
        }
    }

    @Test
    fun checkoutRecoversASubscriptionThatBecomesPendingDuringPreparation() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥120.00", 120_000_000L, "CNY")
                val pending = PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, pending = true)
                fixture.transport.subscriptionsEnabled = true
                fixture.transport.expiry = expiry
                fixture.billing.offered = listOf(yearly)
                fixture.store.loadOffers(fixture.identity)
                assertTrue(fixture.store.state.value.canPurchase(yearly.plan))
                fixture.transport.onPrepare = { fixture.billing.ownedSubscriptions = listOf(pending) }

                fixture.store.purchase(fixture.identity, activity.get(), yearly)

                assertEquals(1, fixture.transport.preparedPurchases)
                assertEquals(0, fixture.billing.launches)
                assertTrue(fixture.store.state.value.hasPendingPayment)
                assertFalse(fixture.store.state.value.canPurchase(yearly.plan))
                assertTrue(fixture.transport.submitted.isEmpty())

                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, false))
                fixture.store.restore(fixture.identity)

                assertEquals(listOf(token), fixture.transport.submitted)
                assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, fixture.store.state.value.subscription?.plan)
                assertFalse(fixture.store.state.value.hasPendingPayment)
                assertEquals(0, fixture.billing.launches)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun checkoutReviewsTheNewSubscriptionWhenTheReplacementTokenChangesDuringPreparation() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥120.00", 120_000_000L, "CNY")
                val previous = PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, false)
                fixture.transport.subscriptionsEnabled = true
                fixture.transport.expiry = expiry
                fixture.transport.recurringPlan = PlayMembershipPlan.MONTHLY_SUBSCRIPTION
                fixture.billing.offered = listOf(yearly)
                fixture.billing.ownedSubscriptions = listOf(previous)
                fixture.store.loadOffers(fixture.identity)
                assertTrue(fixture.store.state.value.canChangePlan)

                val newToken = "changed-play-subscription-during-preparation"
                fixture.transport.onPrepare = {
                    fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, newToken, false))
                    fixture.transport.replyToken = newToken
                    fixture.transport.recurringPlan = PlayMembershipPlan.YEARLY_SUBSCRIPTION
                }
                fixture.store.purchase(fixture.identity, activity.get(), yearly)

                assertEquals(token, fixture.transport.lastPreparation?.get("replacesPurchaseToken")?.jsonPrimitive?.content)
                assertEquals(0, fixture.billing.launches)
                assertEquals(listOf(token, newToken), fixture.transport.submitted)
                assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, fixture.store.state.value.subscription?.plan)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun checkoutPausesSubscriptionsWhenThePostPreparationQueryFails() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥120.00", 120_000_000L, "CNY")
                fixture.transport.subscriptionsEnabled = true
                fixture.billing.offered = listOf(offer, yearly)
                fixture.store.loadOffers(fixture.identity)
                fixture.transport.onPrepare = { fixture.billing.failSubscriptionQuery = true }

                fixture.store.purchase(fixture.identity, activity.get(), yearly)

                val unavailable = fixture.store.state.value
                assertEquals(1, fixture.transport.preparedPurchases)
                assertEquals(0, fixture.billing.launches)
                assertFalse(unavailable.canPurchase(yearly.plan))
                assertTrue(unavailable.canPurchase(offer.plan))
                assertTrue(unavailable.orderInventoryRetryNeeded)
                assertTrue(unavailable.message.orEmpty().contains("暂时无法核对 Google Play 订阅"))

                fixture.billing.failSubscriptionQuery = false
                fixture.transport.onPrepare = {}
                fixture.store.onForeground()
                val recovered = withTimeout(5_000) {
                    fixture.store.state.first { it.canPurchase(yearly.plan) && !it.busy }
                }
                assertFalse(recovered.orderInventoryRetryNeeded)
                assertFalse(recovered.message.orEmpty().contains("暂时无法核对 Google Play 订阅"))
                fixture.store.purchase(fixture.identity, activity.get(), yearly)
                assertEquals(1, fixture.billing.launches)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun aReplacedSubscriptionReceiptRequiresReviewBeforeOpeningPayment() = runBlocking {
        withStore { fixture ->
            val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                val yearly = PlayMembershipOffer(PlayMembershipPlan.YEARLY_SUBSCRIPTION, "¥120.00", 120_000_000L, "CNY")
                fixture.transport.subscriptionsEnabled = true
                fixture.transport.expiry = expiry
                fixture.transport.recurringPlan = PlayMembershipPlan.MONTHLY_SUBSCRIPTION
                fixture.billing.offered = listOf(yearly)
                fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, false))
                fixture.store.loadOffers(fixture.identity)
                assertTrue(fixture.store.state.value.canChangePlan)
                assertEquals(listOf(token), fixture.transport.submitted)

                val newToken = "changed-play-subscription-receipt"
                fixture.billing.ownedSubscriptions =
                    listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, newToken, false))
                fixture.transport.replyToken = newToken
                fixture.transport.recurringPlan = PlayMembershipPlan.YEARLY_SUBSCRIPTION
                fixture.store.purchase(fixture.identity, activity.get(), yearly)

                assertEquals(0, fixture.transport.preparedPurchases)
                assertNull(fixture.billing.launchMarker)
                assertEquals(PlayMembershipPlan.YEARLY_SUBSCRIPTION, fixture.store.state.value.subscription?.plan)
                assertEquals(listOf(token, newToken), fixture.transport.submitted)
            } finally {
                activity.pause().stop().destroy()
            }
        }
    }

    @Test
    fun failedSubscriptionLookupDoesNotKeepThePlanChangeAvailableOrHidePendingOrders() = runBlocking {
        withStore { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.transport.expiry = expiry
            fixture.billing.ownedSubscriptions = listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, false))
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.store.state.value.canChangePlan)

            fixture.billing.failSubscriptionQuery = true
            fixture.billing.owned = listOf(purchase(pending = true, receipt = "pending-one-time-play-receipt"))
            fixture.store.onForeground()
            val unavailable = fixture.waitFor("暂时无法核对 Google Play 订单")
            assertTrue(unavailable.ownsSubscription)
            assertNull(unavailable.subscription)
            assertFalse(unavailable.canChangePlan)
            assertTrue(unavailable.message.orEmpty().contains("暂时无法核对 Google Play 订单"))
            assertTrue(unavailable.message.orEmpty().contains("正在等待付款"))

            fixture.billing.failSubscriptionQuery = false
            fixture.store.restore(fixture.identity)
            assertTrue(fixture.store.state.value.canChangePlan)
            assertEquals(listOf(token, token), fixture.transport.submitted)
        }
    }

    @Test
    fun restoringASuspendedSubscriptionShowsItsStatusAndKeepsPlanChangesClosed() = runBlocking {
        withStore { fixture ->
            fixture.transport.subscriptionsEnabled = true
            fixture.transport.recurringState = PlaySubscriptionState.PAUSED
            fixture.transport.replyStatus = "pending"
            fixture.billing.ownedSubscriptions =
                listOf(PlayMembershipPurchase(SUBSCRIPTION_PRODUCT_ID, marker, token, pending = false, suspended = true))
            fixture.store.restore(fixture.identity)

            val paused = fixture.store.state.value
            assertTrue(paused.ownsSubscription)
            assertEquals(PlaySubscriptionState.PAUSED, paused.subscription?.state)
            assertFalse(paused.canChangePlan)
            assertTrue(paused.canStartCheckout)
            assertFalse(paused.message.orEmpty().contains("等待付款"))
            assertEquals(listOf(token), fixture.transport.submitted)
            assertEquals(false, fixture.sessions.state.value.entitlement?.isActive)
        }
    }

    @Test
    fun providerRepliesArePinnedToCookTheConfiguredLedgerAndExactPurchase() = runBlocking {
        val transport = FixtureTransport(PlayLedger.PRODUCTION)
        val service = PlayMembershipService(PlayLedger.PRODUCTION, transport)
        val capabilities = service.capabilities(credential)
        assertEquals(setOf(PlayMembershipPlan.MONTH), capabilities.products)
        transport.replyLedger = PlayLedger.SANDBOX
        assertTrue(runCatching { service.capabilities(credential) }.exceptionOrNull() is YunlefunAccountException.InvalidResponse)
        transport.replyLedger = PlayLedger.PRODUCTION
        transport.replyApp = "another_app"
        assertTrue(runCatching { service.capabilities(credential) }.exceptionOrNull() is YunlefunAccountException.InvalidResponse)
        transport.replyApp = "cook"
        transport.replyToken = "different-receipt-token"
        assertTrue(runCatching { service.settle(credential, PlayMembershipPlan.MONTH, token) }
            .exceptionOrNull() is YunlefunAccountException.InvalidResponse)
    }

    private suspend fun withStore(
        ledger: PlayLedger = PlayLedger.PRODUCTION,
        initialCapabilitiesFailure: Int? = null,
        initialOneTimeQueryFails: Boolean = false,
        initialOwned: List<PlayMembershipPurchase> = emptyList(),
        initialEntitlementFailure: Int? = null,
        verificationRetryDelaysMs: List<Long> = emptyList(),
        checkoutVault: SecretVault? = null,
        initialCredential: YunlefunCredential = credential,
        elapsedRealtime: () -> Long = android.os.SystemClock::elapsedRealtime,
        block: suspend (Fixture) -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val database = Room.inMemoryDatabaseBuilder(app, CookDatabase::class.java).build()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val transport = FixtureTransport(ledger).apply {
            capabilitiesFailure = initialCapabilitiesFailure
            entitlementFailure = initialEntitlementFailure
        }
        val vault = object : SecretVault {
            override fun readSecret(key: String): String? =
                Json.encodeToString(initialCredential).takeIf { key == AndroidKeystoreVault.KEY_YUNLEFUN_SESSION_JSON }
            override fun writeSecret(key: String, value: String) = Unit
            override fun deleteSecret(key: String) = Unit
        }
        val sessions = YunlefunSessionManager(
            vault, database, YunlefunAccountService(transport), membershipService = MembershipService(transport),
        )
        val billing = FixtureBilling().apply {
            failQuery = initialOneTimeQueryFails
            owned = initialOwned
        }
        val networkChanges = MutableSharedFlow<Unit>()
        val store = PlayMembershipStore(
            sessions, PlayMembershipService(ledger, transport), billing, scope, networkChanges, verificationRetryDelaysMs,
            checkoutVault = checkoutVault, elapsedRealtime = elapsedRealtime,
        )
        val identity = requireNotNull(sessions.state.value.membershipSession)
        try {
            store.onForeground()
            sessions.awaitInitialHydration()
            withTimeout(5_000) {
                networkChanges.subscriptionCount.first { it > 0 }
                transport.firstCapabilities.await()
                store.state.first { it.session == identity && !it.busy }
            }
            block(Fixture(identity, billing, store, transport, sessions, networkChanges))
        } finally {
            scope.cancel()
            database.close()
        }
    }

    private class CheckoutTestVault : SecretVault {
        val values = mutableMapOf<String, String>()
        override fun readSecret(key: String): String? = values[key]
        override fun writeSecret(key: String, value: String) {
            values[key] = value
        }
        override fun deleteSecret(key: String) {
            values.remove(key)
        }
    }

    private data class Fixture(
        val identity: MembershipSession,
        val billing: FixtureBilling,
        val store: PlayMembershipStore,
        val transport: FixtureTransport,
        val sessions: YunlefunSessionManager,
        val networkChanges: MutableSharedFlow<Unit>,
    ) {
        suspend fun waitFor(message: String) = withTimeout(5_000) {
            store.state.first { !it.busy && it.message.orEmpty().contains(message) }
        }

        suspend fun reportPurchases(vararg purchases: PlayMembershipPurchase) {
            val callbackChecked = CompletableDeferred<Unit>()
            transport.onCapabilities = { callbackChecked.complete(Unit) }
            try {
                billing.emit(PlayCheckoutResult.STARTED, *purchases)
                withTimeout(5_000) {
                    callbackChecked.await()
                    store.state.first { !it.busy }
                }
            } finally {
                transport.onCapabilities = {}
            }
        }
    }

    private class FixtureBilling : PlayMembershipBilling {
        private val events = Channel<PlayBillingUpdate>(Channel.BUFFERED)
        override val updates = events.receiveAsFlow()
        private var checkoutId: PlayCheckoutId? = null
        var launches = 0
        var launchMarker: String? = null
        var launchedReplacementToken: String? = null
        var owned = emptyList<PlayMembershipPurchase>()
        var ownedSubscriptions = emptyList<PlayMembershipPurchase>()
        var offered = listOf(offer)
        var unavailableOfferPlans = emptySet<PlayMembershipPlan>()
        var failQuery = false
        var failSubscriptionQuery = false
        var ownedQueries = 0
        var afterReadingOwnedPurchases: suspend () -> Unit = {}
        var beforeStartingLaunch: suspend () -> Unit = {}
        var beforeReturningLaunch: suspend () -> Unit = {}
        override suspend fun offers(plans: Set<PlayMembershipPlan>): List<PlayMembershipOffer> {
            if (plans.any { it in unavailableOfferPlans }) throw PlayBillingUnavailable()
            return offered.filter { it.plan in plans }
        }
        override suspend fun purchases(): List<PlayMembershipPurchase> {
            ownedQueries++
            if (failQuery) throw PlayBillingUnavailable()
            val snapshot = owned
            afterReadingOwnedPurchases()
            return snapshot
        }

        override suspend fun subscriptionPurchases(): List<PlayMembershipPurchase> {
            if (failSubscriptionQuery) throw PlayBillingUnavailable()
            return ownedSubscriptions
        }

        override suspend fun launch(
            activity: ComponentActivity,
            offer: PlayMembershipOffer,
            accountMarker: String,
            replacesPurchaseToken: String?,
            beginCheckout: () -> PlayCheckoutId?,
        ): PlayCheckoutResult {
            beforeStartingLaunch()
            checkoutId = beginCheckout() ?: return PlayCheckoutResult.CANCELLED
            launches++
            launchMarker = accountMarker
            launchedReplacementToken = replacesPurchaseToken
            beforeReturningLaunch()
            return PlayCheckoutResult.STARTED
        }

        suspend fun emit(result: PlayCheckoutResult, vararg purchases: PlayMembershipPurchase) =
            events.send(PlayBillingUpdate(result, purchases.toList(), checkoutId))
    }

    private class FixtureTransport(private val ledger: PlayLedger) : HttpTransport {
        var expiry: Long = 0
        var accountReads = 0
        var entitlementFailure: Int? = null
        var preparedPurchases = 0
        var rejectPreparation = false
        var verificationFailure: HttpResponsePayload? = null
        var replyLedger = ledger
        var replyApp = "cook"
        var replyToken = token
        var replyStatus = "confirmed"
        var subscriptionsEnabled = false
        var recurringPlan = PlayMembershipPlan.YEARLY_SUBSCRIPTION
        var recurringState = PlaySubscriptionState.CANCELLED
        var approvesPlanChange = true
        val firstCapabilities = CompletableDeferred<Unit>()
        var capabilitiesFailure: Int? = null
        var capabilitiesRequests = 0
        var onCapabilities: () -> Unit = {}
        var onPrepare: () -> Unit = {}
        var onVerify: suspend () -> Unit = {}
        var rejectAuthentication: (String, String?) -> HttpResponsePayload? = { _, _ -> null }
        var refreshes = 0
        val verificationAttempts = mutableListOf<String>()
        val submitted = mutableListOf<String>()
        var lastPreparation: JsonObject? = null
        var lastPurchaseBody: JsonObject? = null

        override suspend fun request(
            method: String, url: String, headers: Map<String, String>, jsonBody: String?,
            maxResponseBytes: Int, connectTimeoutMs: Int, readTimeoutMs: Int,
        ): HttpResponsePayload {
            val authorization = headers["Authorization"]
            if (url == YunlefunAccountService.GATEWAY + "token") {
                assertEquals("POST", method)
                val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
                assertEquals("refresh_token", body.getValue("grant_type").jsonPrimitive.content)
                assertEquals(credential.refreshToken, body.getValue("refresh_token").jsonPrimitive.content)
                refreshes++
                return HttpResponsePayload(
                    200,
                    """{"access_token":"renewed-access","refresh_token":"renewed-refresh","sub":"${credential.subject}","expires_in":3600}""",
                )
            }
            if (url == MembershipService.FUNCTIONS_GATEWAY + "account-api") {
                assertEquals(expectedAuthorization(), authorization)
                rejectAuthentication("getAccount", authorization)?.let { return it }
                accountReads++
                entitlementFailure?.let { return HttpResponsePayload(it, "") }
                return HttpResponsePayload(200, """{"membership":{"isActive":${expiry > 0},"expireAt":$expiry}}""")
            }
            if (url != MembershipService.FUNCTIONS_GATEWAY + ledger.endpoint) return HttpResponsePayload(204, "")
            assertEquals("POST", method)
            assertEquals(mapOf("Authorization" to expectedAuthorization()), headers)
            val body = Json.parseToJsonElement(requireNotNull(jsonBody)).jsonObject
            val action = body.getValue("action").jsonPrimitive.content
            if (action == "verifyPurchase" || action == "verifySubscription") {
                verificationAttempts += requireNotNull(body["purchaseToken"]).jsonPrimitive.content
            }
            rejectAuthentication(action, authorization)?.let { return it }
            val context = """"application":"$replyApp","packageName":"fun.yunle.cook","ledgerEnvironment":"${replyLedger.wireValue}""""
            val response = when (action) {
                "getCapabilities" -> {
                    capabilitiesRequests++
                    firstCapabilities.complete(Unit)
                    onCapabilities()
                    capabilitiesFailure?.let { return HttpResponsePayload(it, "") }
                    val subscriptions = if (subscriptionsEnabled) {
                        ""","subscriptions":[{"id":"$SUBSCRIPTION_PRODUCT_ID","basePlanId":"yearly","cycle":"year"},{"id":"$SUBSCRIPTION_PRODUCT_ID","basePlanId":"monthly","cycle":"month"}]"""
                    } else ""
                    """{$context,"accountMarker":"$marker","products":[{"id":"cook_member_month","cycle":"month","purchaseOptionId":"buy"}]$subscriptions}"""
                }
                "preparePurchase" -> {
                    preparedPurchases++
                    lastPreparation = body
                    onPrepare()
                    val product = body["productId"]?.jsonPrimitive?.content
                    val recurring = product == SUBSCRIPTION_PRODUCT_ID
                    val replacing = body["replacesPurchaseToken"] != null
                    val keys = buildSet {
                        addAll(listOf("action", "productId"))
                        if (recurring) add("basePlanId")
                        if (replacing) add("replacesPurchaseToken")
                    }
                    assertEquals(keys, body.keys)
                    if (rejectPreparation) """{"code":"play_unavailable"}"""
                    else {
                        val base = if (recurring) ""","basePlanId":${body["basePlanId"]}""" else ""
                        val mode = if (replacing && approvesPlanChange) ""","replacementMode":"WITHOUT_PRORATION"""" else ""
                        """{$context,"accountMarker":"$marker","productId":"$product"$base$mode}"""
                    }
                }
                "verifyPurchase", "verifySubscription" -> {
                    lastPurchaseBody = body
                    submitted += requireNotNull(body["purchaseToken"]).jsonPrimitive.content
                    onVerify()
                    verificationFailure?.let { return it }
                    val digest = MessageDigest.getInstance("SHA-256").digest(replyToken.encodeToByteArray())
                        .joinToString("") { "%02x".format(it) }
                    if (body["action"]?.jsonPrimitive?.content == "verifySubscription") {
                        val period = expiry.takeIf { it > 0 }
                        """{$context,"productId":"$SUBSCRIPTION_PRODUCT_ID","purchaseReference":"$digest","status":"$replyStatus","basePlanId":"${recurringPlan.basePlanId}","cycle":"${recurringPlan.cycle}","state":"${recurringState.wireValue}","autoRenewing":false,"periodExpiresAt":$period}"""
                    } else {
                        """{$context,"productId":"cook_member_month","purchaseReference":"$digest","status":"$replyStatus"}"""
                    }
                }
                "getEntitlement" -> {
                    entitlementFailure?.let { return HttpResponsePayload(it, "") }
                    """{$context,"membership":{"isActive":${expiry > 0},"expireAt":$expiry}}"""
                }
                else -> error("Unexpected Play action")
            }
            return HttpResponsePayload(200, response)
        }

        private fun expectedAuthorization() = if (refreshes == 0) originalAuthorization else "Bearer renewed-access"
    }

    companion object {
        private val credential = YunlefunCredential("fixture-access", "fixture-refresh", "fixture-user", Long.MAX_VALUE, "fixture")
        private val originalAuthorization = "Bearer ${credential.accessToken}"
        private val marker = "1".repeat(64)
        private const val token = "play-purchase-receipt-12345"
        private val expiry = System.currentTimeMillis() + 86_400_000L
        private val offer = PlayMembershipOffer(PlayMembershipPlan.MONTH, "¥12.00", 12_000_000, "CNY")

        private fun purchase(pending: Boolean = false, account: String = marker, receipt: String = token) =
            PlayMembershipPurchase(PlayMembershipPlan.MONTH.productId, account, receipt, pending)
    }
}
