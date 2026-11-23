package cn.yunyoujun.cook.platform

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import cn.yunyoujun.cook.data.local.SecretVault
import cn.yunyoujun.cook.data.remote.MembershipCheckReason
import cn.yunyoujun.cook.data.remote.MembershipCheckResult
import cn.yunyoujun.cook.data.remote.MembershipEntitlement
import cn.yunyoujun.cook.data.remote.MembershipSession
import cn.yunyoujun.cook.data.remote.PlayLedger
import cn.yunyoujun.cook.data.remote.PlayMembershipCapabilities
import cn.yunyoujun.cook.data.remote.PlayMembershipPlan
import cn.yunyoujun.cook.data.remote.PlayMembershipService
import cn.yunyoujun.cook.data.remote.PlayMembershipUnavailable
import cn.yunyoujun.cook.data.remote.PlaySettlement
import cn.yunyoujun.cook.data.remote.PlaySubscriptionState
import cn.yunyoujun.cook.data.remote.PlaySubscriptionStatus
import cn.yunyoujun.cook.data.remote.SUBSCRIPTION_PRODUCT_ID
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private const val EMPTY_CHECKOUT_GRACE_MS = 30_000L
private const val SAVED_CHECKOUT_LIFETIME_MS = 24 * 60 * 60 * 1000L

internal class PlayCheckout(
    val plan: PlayMembershipPlan,
    val replacedTokenDigest: String?,
    val startedAtMs: Long = System.currentTimeMillis(),
) {
    val id = PlayCheckoutId()

    fun matches(purchase: PlayMembershipPurchase, accountMarker: String): Boolean =
        purchase.productId == plan.productId && purchase.accountMarker == accountMarker &&
            (replacedTokenDigest == null || digest(purchase.token) != replacedTokenDigest)
}

private fun digest(value: String): String = YunlefunSSO.sha256Base64Url(value.toByteArray(Charsets.UTF_8))

internal data class PlayMembershipUiState(
    val session: MembershipSession? = null,
    val offers: List<PlayMembershipOffer> = emptyList(),
    val offerLoadFailed: Boolean = false,
    val busy: Boolean = false,
    val checkout: PlayCheckout? = null,
    val returnedCheckoutId: PlayCheckoutId? = null,
    val firstMissingCheckoutOrderAtMs: Long? = null,
    val hasPendingPayment: Boolean = false,
    val hasPendingVerification: Boolean = false,
    val confirmationPendingProducts: Set<String> = emptySet(),
    val orderInventoryRetryNeeded: Boolean = false,
    val orderVerificationRetryNeeded: Boolean = false,
    val entitlementRetryReason: MembershipCheckReason? = null,
    val oneTimeOrdersChecked: Boolean = false,
    val subscriptionOrdersChecked: Boolean = false,
    val checkoutMessage: String? = null,
    val orderMessage: String? = null,
    val sandboxEntitlement: MembershipEntitlement? = null,
    val sandboxEntitlementMessage: String? = null,
    val supportsSubscriptions: Boolean = false,
    val ownsSubscription: Boolean = false,
    val subscription: PlaySubscriptionStatus? = null,
    val canChangePlan: Boolean = false,
) {
    val awaitingCheckout: Boolean get() = checkout != null
    val canStartCheckout: Boolean get() = !awaitingCheckout && !hasPendingPayment && !hasPendingVerification
    val canReconcileOrders: Boolean get() = checkout == null || checkout.id === returnedCheckoutId
    val needsOrderRecovery: Boolean
        get() = firstMissingCheckoutOrderAtMs != null || orderInventoryRetryNeeded || orderVerificationRetryNeeded

    fun ordersCheckedFor(plan: PlayMembershipPlan): Boolean =
        if (plan.basePlanId == null) oneTimeOrdersChecked else subscriptionOrdersChecked

    fun canPurchase(plan: PlayMembershipPlan): Boolean =
        canStartCheckout && ordersCheckedFor(plan) && plan.productId !in confirmationPendingProducts

    val message: String?
        get() = listOfNotNull(
            checkoutMessage,
            if (offerLoadFailed) {
                if (offers.isEmpty()) "暂时无法加载 Google Play 商品。" else "部分 Google Play 商品暂时无法加载。"
            } else null,
            "Google Play 正在等待付款。付款完成后，返回 App 或恢复购买即可核对。".takeIf { hasPendingPayment },
            "已购订单待核对，请恢复 Google Play 购买。".takeIf { hasPendingVerification },
            "会员已入账，Google Play 确认待重试，可再次恢复购买。".takeIf { confirmationPendingProducts.isNotEmpty() },
            orderMessage,
            sandboxEntitlementMessage,
        ).takeIf { it.isNotEmpty() }?.joinToString("\n")
}

/** One BillingClient listener for the app process, including when the membership page is closed. */
internal class PlayMembershipStore(
    private val sessions: YunlefunSessionManager,
    private val service: PlayMembershipService,
    private val billing: PlayMembershipBilling,
    private val scope: CoroutineScope,
    networkChanges: Flow<Unit> = emptyFlow(),
    private val recoveryRetryDelaysMs: List<Long> = listOf(10_000L, 20_000L, 40_000L),
    private val checkoutVault: SecretVault? = null,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
) {
    val ledger: PlayLedger get() = service.ledger
    private val mutableState = MutableStateFlow(PlayMembershipUiState())
    val state = mutableState.asStateFlow()
    private val operations = Mutex()
    private val foreground = MutableStateFlow(false)
    private var recoveryJob: Job? = null
    private class ReconciliationRequest(val checkoutOnReturn: PlayCheckout?)
    private var requestedReconciliation: ReconciliationRequest? = null
    private var subscriptionTokenForPlanChange: String? = null
    private enum class PendingStage { PAYMENT, VERIFICATION, CONFIRMATION, SUBSCRIPTION_STATUS }
    private data class PendingOrder(val productId: String, val stage: PendingStage, val retryable: Boolean = false)
    private var pendingOrders = emptyMap<String, PendingOrder>()

    @Serializable
    private data class SavedCheckout(val plan: String, val replacedTokenDigest: String?, val startedAtMs: Long)

    init {
        scope.launch {
            sessions.state.map { it.membershipSession }.distinctUntilChanged().collectLatest { identity ->
                recoveryJob?.cancel()
                recoveryJob = null
                requestedReconciliation = null
                resetForSession(identity)
                if (identity != null) {
                    requestOrderReconciliation(identity)
                    networkChanges.collect { requestOrderReconciliation(identity) }
                }
            }
        }
        scope.launch {
            billing.updates.collect { update -> handleBillingUpdate(update) }
        }
        scope.launch {
            combine(foreground, state) { active, current ->
                // A successful but empty lookup starts its own window even after earlier network retries.
                current.session?.takeIf {
                    active && current.canReconcileOrders && (current.needsOrderRecovery || current.entitlementRetryReason != null)
                }?.let { it to current.firstMissingCheckoutOrderAtMs }
            }.distinctUntilChanged().collectLatest { recovery ->
                val identity = recovery?.first ?: return@collectLatest
                for (retryDelayMs in recoveryRetryDelaysMs) {
                    delay(retryDelayMs)
                    operate(identity, showErrors = false) {
                        val current = state.value
                        if (!foreground.value || !current.canReconcileOrders) return@operate
                        if (current.needsOrderRecovery) {
                            recover(identity, refreshEntitlement = true)
                        } else {
                            current.entitlementRetryReason?.let { refreshMembershipEntitlement(identity, it) }
                        }
                    }
                }
            }
        }
    }

    @MainThread
    fun onForeground() {
        foreground.value = true
        val identity = sessions.state.value.membershipSession ?: return
        // A failed inventory request after returning can still be retried for this checkout.
        updateState(identity) { it.copy(returnedCheckoutId = it.checkout?.id) }
        requestOrderReconciliation(identity)
    }

    @MainThread
    fun onBackground() {
        foreground.value = false
    }

    private fun requestOrderReconciliation(identity: MembershipSession) {
        val current = state.value
        if (!foreground.value || current.session != identity || !sessions.isCurrentMembershipSession(identity)) return
        if (!current.canReconcileOrders) return
        requestedReconciliation = ReconciliationRequest(current.checkout)
        if (recoveryJob?.isActive == true) return
        recoveryJob = scope.launch {
            while (foreground.value && sessions.isCurrentMembershipSession(identity)) {
                val request = requestedReconciliation ?: break
                requestedReconciliation = null
                operate(identity, showErrors = false) {
                    // A request queued before a new Play flow opened cannot settle that flow.
                    val checkout = state.value.checkout
                    if (foreground.value && (checkout == null || checkout === request.checkoutOnReturn)) {
                        recover(identity, refreshEntitlement = true)
                    }
                }
            }
        }
    }

    @MainThread
    suspend fun loadOffers(identity: MembershipSession) = operate(identity) {
        updateState(identity) { it.copy(offers = emptyList(), offerLoadFailed = false, checkoutMessage = null) }
        val capabilities = sessions.withMembershipSessionRetryingAuth(identity, service::capabilities)
        val results = capabilities.products.groupBy { it.basePlanId != null }.values.map { plans ->
            queryPlay({ billing.offers(plans.toSet()) })
        }
        val offers = results.filterNotNull().flatten().sortedBy { it.plan.ordinal }
        val offerLoadFailed = results.any { it == null }
        updateState(identity) {
            it.copy(
                offers = offers,
                offerLoadFailed = offerLoadFailed,
                supportsSubscriptions = capabilities.products.any { plan -> plan.basePlanId != null },
                checkoutMessage = if (offers.isEmpty() && !offerLoadFailed) "Google Play 暂无可购买的会员方案。" else null,
            )
        }
        if (offers.any { !state.value.ordersCheckedFor(it.plan) }) {
            recover(identity, knownCapabilities = capabilities)
        }
    }

    @MainThread
    suspend fun purchase(identity: MembershipSession, activity: ComponentActivity, offer: PlayMembershipOffer) = operate(identity) {
        val current = state.value
        if (!current.canPurchase(offer.plan) || offer !in current.offers) return@operate
        val subscription = offer.plan.basePlanId != null
        var previousToken: String? = null
        if (subscription) {
            val subscriptions = querySubscriptionsBeforeCheckout(identity) ?: return@operate
            if (current.ownsSubscription || subscriptions.isNotEmpty()) {
                val owned = subscriptions.singleOrNull()
                if (owned == null || owned.pending || owned.suspended ||
                    !current.canChangePlan || owned.token != subscriptionTokenForPlanChange
                ) {
                    recover(identity, explicit = true)
                    updateState(identity) { it.copy(checkoutMessage = "请核对当前 Google Play 订阅方案后再继续。") }
                    return@operate
                }
                if (current.subscription?.plan == offer.plan) return@operate
                val capabilities = sessions.withMembershipSessionRetryingAuth(identity, service::capabilities)
                if (owned.accountMarker != null && owned.accountMarker != capabilities.accountMarker) {
                    clearVerifiedSubscription(identity)
                    updateState(identity) { it.copy(checkoutMessage = "请登录购买时使用的云乐坊账号后更改方案。") }
                    return@operate
                }
                previousToken = owned.token
            }
        }
        updateState(identity) { it.copy(checkoutMessage = null) }
        val accountMarker = sessions.withMembershipSessionRetryingAuth(identity) { credential ->
            service.preparePurchase(credential, offer.plan, previousToken)
        }
        if (!sessions.isCurrentMembershipSession(identity)) return@operate
        if (subscription) {
            // Play inventory can change while the server records the purchase intent.
            val subscriptions = querySubscriptionsBeforeCheckout(identity) ?: return@operate
            val owned = subscriptions.singleOrNull()
            val unchanged = if (previousToken == null) {
                subscriptions.isEmpty()
            } else {
                owned != null && owned.token == previousToken && !owned.pending && !owned.suspended &&
                    (owned.accountMarker == null || owned.accountMarker == accountMarker)
            }
            if (!unchanged) {
                recover(identity, explicit = true)
                updateState(identity) { it.copy(checkoutMessage = "请核对当前 Google Play 订阅方案后再继续。") }
                return@operate
            }
        } else if (!checkOwnedPassesBeforeCheckout(identity, accountMarker)) return@operate
        if (!sessions.isCurrentMembershipSession(identity)) return@operate
        val checkout = PlayCheckout(offer.plan, previousToken?.let(::digest))
        val result = billing.launch(activity, offer, accountMarker, previousToken) {
            updateState(identity) {
                val saved = SavedCheckout(checkout.plan.name, checkout.replacedTokenDigest, checkout.startedAtMs)
                checkoutVault?.writeSecret(checkoutKey(identity), Json.encodeToString(saved))
                it.copy(checkout = checkout, returnedCheckoutId = null)
            }
            checkout.id.takeIf { state.value.checkout === checkout && sessions.isCurrentMembershipSession(identity) }
        }
        if (result != PlayCheckoutResult.STARTED) {
            updateState(identity) { if (it.checkout === checkout) clearCheckout(identity, it) else it }
        }
        when (result) {
            PlayCheckoutResult.STARTED -> Unit
            PlayCheckoutResult.CANCELLED -> updateState(identity) { it.copy(checkoutMessage = "已取消购买。") }
            PlayCheckoutResult.ALREADY_OWNED -> recover(identity, explicit = true)
            PlayCheckoutResult.PRICE_CHANGED -> updateState(identity) {
                it.copy(offers = emptyList(), checkoutMessage = "Google Play 商品或价格已变更，请重新加载后确认。")
            }
            PlayCheckoutResult.FAILED -> updateState(identity) { it.copy(checkoutMessage = "暂时无法打开 Google Play 购买，请重试。") }
        }
    }

    private suspend fun querySubscriptionsBeforeCheckout(identity: MembershipSession): List<PlayMembershipPurchase>? =
        queryPlay(billing::subscriptionPurchases) { failure ->
            clearVerifiedSubscription(identity)
            updateState(identity) {
                it.copy(
                    subscriptionOrdersChecked = false,
                    orderInventoryRetryNeeded = it.orderInventoryRetryNeeded || failure.retryable,
                    checkoutMessage = null,
                    orderMessage = "暂时无法核对 Google Play 订阅，请恢复购买后重试。",
                )
            }
        }?.filter { it.productId == SUBSCRIPTION_PRODUCT_ID }

    private suspend fun checkOwnedPassesBeforeCheckout(identity: MembershipSession, accountMarker: String): Boolean {
        val ownedPasses = queryPlay(billing::purchases) { failure ->
            updateState(identity) {
                it.copy(
                    oneTimeOrdersChecked = false,
                    orderInventoryRetryNeeded = it.orderInventoryRetryNeeded || failure.retryable,
                    checkoutMessage = "暂时无法核对 Google Play 单次订单。",
                )
            }
        } ?: return false
        val existing = ownedPasses.filter { it.accountMarker == accountMarker }
        if (existing.isEmpty()) return true

        // Finish handling older orders before the user decides whether to make another payment.
        updateState(identity) { it.copy(checkoutMessage = "发现已有 Google Play 订单，请先恢复购买。") }
        recover(identity, reported = existing)
        return false
    }

    @MainThread
    fun requestRestore(identity: MembershipSession) {
        scope.launch { restore(identity) }
    }

    @MainThread
    suspend fun restore(identity: MembershipSession) = operate(identity) {
        updateState(identity) { it.copy(checkoutMessage = null) }
        recover(identity, explicit = true)
    }

    private suspend fun handleBillingUpdate(update: PlayBillingUpdate) {
        val identity = sessions.state.value.membershipSession ?: return
        operate(identity, showErrors = false) {
            val sameCheckout = update.checkoutId != null && state.value.checkout?.id === update.checkoutId
            when (update.result) {
                PlayCheckoutResult.STARTED ->
                    recover(identity, update.purchases.takeIf { it.isNotEmpty() }, settleCheckout = sameCheckout)
                PlayCheckoutResult.ALREADY_OWNED -> recover(identity, explicit = sameCheckout, settleCheckout = sameCheckout)
                PlayCheckoutResult.CANCELLED -> if (sameCheckout) {
                    updateState(identity) { clearCheckout(identity, it).copy(checkoutMessage = "已取消购买。") }
                }
                PlayCheckoutResult.FAILED, PlayCheckoutResult.PRICE_CHANGED -> if (sameCheckout) {
                    updateState(identity) {
                        clearCheckout(identity, it).copy(checkoutMessage = "Google Play 暂未完成购买，可恢复购买后重试。")
                    }
                }
            }
        }
    }

    private suspend fun recover(
        identity: MembershipSession,
        reported: List<PlayMembershipPurchase>? = null,
        explicit: Boolean = false,
        refreshEntitlement: Boolean = false,
        knownCapabilities: PlayMembershipCapabilities? = null,
        settleCheckout: Boolean = true,
    ) {
        val messages = linkedSetOf<String>()
        var checkEntitlement = explicit || refreshEntitlement || ledger == PlayLedger.SANDBOX
        var checkedOneTimeOrders = false
        var checkedSubscriptionOrders = false
        var checkedCheckout = false
        var useInventory = reported == null
        var hasAccountReport = false
        var retryInventory = false
        var firstMissingCheckoutOrderAtMs = state.value.firstMissingCheckoutOrderAtMs
        val activeCheckout = state.value.checkout
        val checkout = activeCheckout.takeIf { settleCheckout }
        val hadSubscription = state.value.ownsSubscription
        val unresolved = pendingOrders.toMutableMap()
        if (useInventory) clearVerifiedSubscription(identity)
        try {
            val capabilities = knownCapabilities ?: sessions.withMembershipSessionRetryingAuth(identity, service::capabilities)
            val subscriptionsEnabled = capabilities.products.any { it.basePlanId != null }
            val enabledProducts = capabilities.products.mapTo(mutableSetOf()) { it.productId }
            val accountReports = reported?.filter {
                it.accountMarker == capabilities.accountMarker && it.productId in enabledProducts
            }
            hasAccountReport = !accountReports.isNullOrEmpty()
            if (reported != null && !hasAccountReport) {
                // An unmarked subscription can be recovered through inventory and server verification.
                // Leave a checkout in progress alone until its own receipt or a normal inventory refresh.
                val legacySubscription = subscriptionsEnabled && reported.any {
                    it.productId == SUBSCRIPTION_PRODUCT_ID && it.accountMarker == null
                }
                if (activeCheckout != null || !legacySubscription) return
                useInventory = true
            }
            val hasSubscriptionReport = accountReports.orEmpty().any { it.productId == SUBSCRIPTION_PRODUCT_ID }
            if (!subscriptionsEnabled || (reported != null && useInventory) || hasSubscriptionReport) {
                clearVerifiedSubscription(identity)
            }
            val onInventoryFailure: (PlayBillingUnavailable) -> Unit = { retryInventory = retryInventory || it.retryable }
            val passes = if (useInventory) queryPlay(billing::purchases, onInventoryFailure) else null
            val subscriptions = if (useInventory && subscriptionsEnabled) {
                queryPlay(billing::subscriptionPurchases, onInventoryFailure)
            } else null
            // Only a successful inventory query can confirm that an unreported pending order disappeared.
            unresolved.values.removeAll { order ->
                if (order.productId == SUBSCRIPTION_PRODUCT_ID) subscriptions != null else passes != null
            }
            checkedOneTimeOrders = passes != null
            checkedSubscriptionOrders = subscriptions != null
            val checkedOwnedPurchases = checkedOneTimeOrders && (!subscriptionsEnabled || checkedSubscriptionOrders)
            val checkoutInventory = checkout?.let { if (it.plan.basePlanId == null) passes else subscriptions }
            val subscriptionLookupFailed = hadSubscription && subscriptionsEnabled && subscriptions == null
            val checkoutLookupFailed = checkout != null && checkoutInventory == null
            val restoreLookupFailed = !checkedOwnedPurchases && (explicit || subscriptionLookupFailed)
            if (useInventory && (checkoutLookupFailed || restoreLookupFailed)) {
                messages += "暂时无法核对 Google Play 订单，请重试。"
            }
            val purchases = (if (useInventory) passes.orEmpty() + subscriptions.orEmpty() else accountReports.orEmpty())
                .distinctBy { it.token }
            if (checkout != null) {
                checkedCheckout = purchases.any { checkout.matches(it, capabilities.accountMarker) }
                if (!checkedCheckout && checkoutInventory != null &&
                    (explicit || state.value.returnedCheckoutId === checkout.id)
                ) {
                    val now = elapsedRealtime()
                    val firstMissing = firstMissingCheckoutOrderAtMs ?: now
                    checkedCheckout = now - firstMissing >= EMPTY_CHECKOUT_GRACE_MS
                    if (checkedCheckout) messages += "暂未查到本次订单。若已付款，可稍后恢复购买。"
                    else firstMissingCheckoutOrderAtMs = firstMissing
                }
            }
            val subscriptionOwned = subscriptionsEnabled && purchases.any { it.productId == SUBSCRIPTION_PRODUCT_ID }
            val retainSubscription = subscriptionsEnabled && (!useInventory || subscriptions == null)
            val subscriptionForDisplay = purchases.singleOrNull { it.productId == SUBSCRIPTION_PRODUCT_ID }
            updateState(identity) {
                it.copy(
                    supportsSubscriptions = subscriptionsEnabled,
                    ownsSubscription = subscriptionOwned || (retainSubscription && it.ownsSubscription),
                )
            }
            if (purchases.isNotEmpty()) {
                for (purchase in purchases) {
                    val plans = capabilities.products.filter { it.productId == purchase.productId }
                    if (plans.isEmpty()) continue
                    val recurring = purchase.productId == SUBSCRIPTION_PRODUCT_ID
                    val hasAccountMarker = purchase.accountMarker == capabilities.accountMarker
                    if (!hasAccountMarker && !(recurring && purchase.accountMarker == null)) {
                        if (explicit || recurring) messages += "有订单不属于当前云乐坊账号，请登录购买时使用的账号恢复。"
                        continue
                    }
                    val previous = pendingOrders[purchase.token]?.takeIf { it.productId == purchase.productId }
                    val awaitingConfirmation = previous?.takeIf { it.stage == PendingStage.CONFIRMATION }
                    val belongsToAccount = hasAccountMarker ||
                        (previous != null && previous.stage != PendingStage.SUBSCRIPTION_STATUS)
                    if (purchase.pending) {
                        if (belongsToAccount) {
                            unresolved[purchase.token] = awaitingConfirmation ?: PendingOrder(purchase.productId, PendingStage.PAYMENT)
                        } else {
                            // A legacy subscription may lack the account marker. It cannot block this account's other plans.
                            messages += "Google Play 订阅正在等待付款，请稍后恢复购买。"
                        }
                        continue
                    }
                    unresolved.remove(purchase.token)
                    val unfinished = awaitingConfirmation ?: PendingOrder(purchase.productId, PendingStage.VERIFICATION)
                        .takeIf { belongsToAccount && !purchase.acknowledged && !purchase.suspended }
                    try {
                        val result = verifyPurchase(identity, purchase, plans, showSubscription = purchase === subscriptionForDisplay)
                        if (recurring) checkEntitlement = true
                        if (purchase.suspended) continue
                        when (result) {
                            PlaySettlement.PENDING -> {
                                if (unfinished != null) {
                                    unresolved[purchase.token] = unfinished.copy(retryable = true)
                                } else {
                                    messages += "Google Play 订单暂未确认，请稍后恢复购买。"
                                }
                            }
                            PlaySettlement.CANCELLED -> {
                                checkEntitlement = true
                                messages += "Google Play 订单已取消。"
                            }
                            PlaySettlement.COMMITTED -> {
                                checkEntitlement = true
                                unresolved[purchase.token] = PendingOrder(
                                    purchase.productId, PendingStage.CONFIRMATION, retryable = true,
                                )
                            }
                            PlaySettlement.CONFIRMED -> {
                                checkEntitlement = true
                                messages += "Google Play 订单已确认。"
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val retryable = error.isRetryablePlayFailure()
                        if (unfinished != null) {
                            unresolved[purchase.token] = unfinished.copy(retryable = retryable)
                        } else if (recurring && retryable) {
                            // Reading a subscription's status can fail even after Play acknowledged the order.
                            unresolved[purchase.token] = PendingOrder(
                                purchase.productId, PendingStage.SUBSCRIPTION_STATUS, retryable = true,
                            )
                        }
                        if (error is YunlefunAccountException.ExpiredSession) {
                            error.message?.let(messages::add)
                        } else if (unfinished == null) {
                            messages += "有订单待核对，请稍后恢复购买。"
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            retryInventory = retryInventory || error.isRetryablePlayFailure()
            if (explicit || reported != null || hadSubscription || checkout != null) {
                messages += if (error is YunlefunAccountException.ExpiredSession) error.message.orEmpty()
                else "暂时无法核对 Google Play 订单，请重试。"
            }
        }
        val confirmationPendingProducts = unresolved.values
            .filter { it.stage == PendingStage.CONFIRMATION }
            .mapTo(mutableSetOf()) { it.productId }
        if (checkEntitlement) {
            val reason = if (explicit || messages.isNotEmpty() || confirmationPendingProducts.isNotEmpty()) MembershipCheckReason.PLAY_ORDER
            else MembershipCheckReason.SCREEN
            refreshMembershipEntitlement(identity, reason)
        }
        if (useInventory || hasAccountReport || retryInventory || messages.isNotEmpty() || unresolved != pendingOrders) {
            updateState(identity) {
                pendingOrders = unresolved
                val settled = if (checkedCheckout && it.checkout === checkout) clearCheckout(identity, it) else it
                settled.copy(
                    firstMissingCheckoutOrderAtMs = firstMissingCheckoutOrderAtMs.takeIf { settled.checkout != null },
                    checkoutMessage = it.checkoutMessage.takeUnless { hasAccountReport },
                    hasPendingPayment = unresolved.values.any { order -> order.stage == PendingStage.PAYMENT },
                    hasPendingVerification = unresolved.values.any { order -> order.stage == PendingStage.VERIFICATION },
                    confirmationPendingProducts = confirmationPendingProducts,
                    orderInventoryRetryNeeded = retryInventory || (!useInventory && it.orderInventoryRetryNeeded),
                    orderVerificationRetryNeeded = unresolved.values.any { order -> order.retryable },
                    oneTimeOrdersChecked = if (useInventory) checkedOneTimeOrders else it.oneTimeOrdersChecked,
                    subscriptionOrdersChecked = if (useInventory) checkedSubscriptionOrders else it.subscriptionOrdersChecked,
                    orderMessage = messages.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                )
            }
        }
    }

    private suspend fun refreshMembershipEntitlement(identity: MembershipSession, reason: MembershipCheckReason) {
        if (ledger == PlayLedger.PRODUCTION) {
            val outcome = sessions.checkMembership(reason, identity)
            val retryReason = reason.takeIf { outcome == MembershipCheckResult.RETRYABLE_FAILURE }
            updateState(identity) { it.copy(entitlementRetryReason = retryReason) }
            return
        }

        try {
            val entitlement = sessions.withMembershipSessionRetryingAuth(identity, service::sandboxEntitlement)
            updateState(identity) {
                it.copy(sandboxEntitlement = entitlement, sandboxEntitlementMessage = null, entitlementRetryReason = null)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val retryReason = reason.takeIf { error.isRetryablePlayFailure() }
            val message = if (error is YunlefunAccountException.ExpiredSession) error.message else "暂时无法核对会员有效期。"
            updateState(identity) { it.copy(entitlementRetryReason = retryReason, sandboxEntitlementMessage = message) }
        }
    }

    private fun Exception.isRetryablePlayFailure(): Boolean = when (this) {
        is IOException -> true
        is PlayMembershipUnavailable -> retryable
        is YunlefunAccountException.Unavailable -> retryable
        else -> false
    }

    private suspend fun <T> queryPlay(query: suspend () -> T, onUnavailable: (PlayBillingUnavailable) -> Unit = {}): T? =
        try {
            query()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: PlayBillingUnavailable) {
            onUnavailable(error)
            null
        }

    private suspend fun verifyPurchase(
        identity: MembershipSession,
        purchase: PlayMembershipPurchase,
        plans: List<PlayMembershipPlan>,
        showSubscription: Boolean,
    ): PlaySettlement {
        if (purchase.productId != SUBSCRIPTION_PRODUCT_ID) {
            return sessions.withMembershipSessionRetryingAuth(identity) { credential ->
                service.settle(credential, plans.single(), purchase.token)
            }
        }
        val result = sessions.withMembershipSessionRetryingAuth(identity) { credential ->
            service.verifySubscription(credential, purchase.token)
        }
        if (result.subscription.plan !in plans) throw PlayMembershipUnavailable()
        if (showSubscription) {
            val subscription = result.subscription
            val canChange = !purchase.suspended && result.settlement == PlaySettlement.CONFIRMED &&
                subscription.state in setOf(PlaySubscriptionState.ACTIVE, PlaySubscriptionState.CANCELLED) &&
                (subscription.periodExpiresAt ?: 0L) > System.currentTimeMillis()
            updateState(identity) {
                subscriptionTokenForPlanChange = purchase.token.takeIf { canChange }
                it.copy(subscription = subscription, canChangePlan = canChange)
            }
        }
        return result.settlement
    }

    private fun clearVerifiedSubscription(identity: MembershipSession) {
        updateState(identity) {
            subscriptionTokenForPlanChange = null
            it.copy(subscription = null, canChangePlan = false)
        }
    }

    private fun resetForSession(identity: MembershipSession?) {
        if (state.value.session == identity) return
        subscriptionTokenForPlanChange = null
        pendingOrders = emptyMap()
        val checkout = identity?.let(::restoreCheckout)
        mutableState.value = PlayMembershipUiState(session = identity, checkout = checkout, returnedCheckoutId = checkout?.id)
    }

    private fun checkoutKey(identity: MembershipSession): String = "play_checkout_${ledger.wireValue}_${digest(identity.subject)}"

    private fun clearCheckout(identity: MembershipSession, current: PlayMembershipUiState): PlayMembershipUiState {
        runCatching { checkoutVault?.deleteSecret(checkoutKey(identity)) }
        return current.copy(checkout = null, returnedCheckoutId = null, firstMissingCheckoutOrderAtMs = null)
    }

    private fun restoreCheckout(identity: MembershipSession): PlayCheckout? {
        val key = checkoutKey(identity)
        val raw = runCatching { checkoutVault?.readSecret(key) }.getOrNull() ?: return null
        val saved = runCatching { Json.decodeFromString<SavedCheckout>(raw) }.getOrNull()
        val plan = saved?.let { data -> PlayMembershipPlan.entries.firstOrNull { it.name == data.plan } }
        if (saved == null || plan == null ||
            System.currentTimeMillis() - saved.startedAtMs !in 0 until SAVED_CHECKOUT_LIFETIME_MS
        ) {
            runCatching { checkoutVault?.deleteSecret(key) }
            return null
        }
        return PlayCheckout(plan, saved.replacedTokenDigest, saved.startedAtMs)
    }

    private suspend fun operate(
        identity: MembershipSession,
        showErrors: Boolean = true,
        action: suspend () -> Unit,
    ) = operations.withLock {
        if (!sessions.isCurrentMembershipSession(identity)) return@withLock
        resetForSession(identity)
        updateState(identity) { it.copy(busy = true) }
        try {
            action()
        } catch (cancelled: CancellationException) {
            // An account change is represented as cancellation by the shared session manager.
            if (sessions.isCurrentMembershipSession(identity)) throw cancelled
        } catch (error: Exception) {
            if (showErrors) updateState(identity) {
                it.copy(checkoutMessage = if (error is YunlefunAccountException.ExpiredSession) error.message
                else "暂时无法连接 Google Play 会员，请重试。")
            }
        } finally {
            updateState(identity) { it.copy(busy = false) }
        }
    }

    private fun updateState(identity: MembershipSession, change: (PlayMembershipUiState) -> PlayMembershipUiState) {
        val current = state.value
        if (current.session == identity && sessions.isCurrentMembershipSession(identity)) mutableState.value = change(current)
    }
}
