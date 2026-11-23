package cn.yunyoujun.cook.platform

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import cn.yunyoujun.cook.data.remote.PlayLedger
import cn.yunyoujun.cook.data.remote.PlayMembershipPlan
import cn.yunyoujun.cook.data.remote.SUBSCRIPTION_PRODUCT_ID
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingFlowParams.ProductDetailsParams.SubscriptionProductReplacementParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal data class PlayMembershipOffer(
    val plan: PlayMembershipPlan,
    val formattedPrice: String,
    val priceMicros: Long,
    val currency: String,
)

internal class PlayMembershipPurchase(
    val productId: String,
    val accountMarker: String?,
    val token: String,
    val pending: Boolean,
    val suspended: Boolean = false,
    val acknowledged: Boolean = false,
)

internal enum class PlayCheckoutResult { STARTED, CANCELLED, ALREADY_OWNED, PRICE_CHANGED, FAILED }

internal class PlayCheckoutId

internal data class PlayBillingUpdate(
    val result: PlayCheckoutResult,
    val purchases: List<PlayMembershipPurchase> = emptyList(),
    val checkoutId: PlayCheckoutId? = null,
)

internal class PlayBillingUnavailable(val retryable: Boolean = true) : Exception()

internal interface PlayMembershipBilling {
    val updates: Flow<PlayBillingUpdate>
    /** Fetch plans of a single Play product type. */
    suspend fun offers(plans: Set<PlayMembershipPlan>): List<PlayMembershipOffer>
    suspend fun purchases(): List<PlayMembershipPurchase>
    suspend fun subscriptionPurchases(): List<PlayMembershipPurchase>
    suspend fun launch(
        activity: ComponentActivity,
        offer: PlayMembershipOffer,
        accountMarker: String,
        replacesPurchaseToken: String? = null,
        beginCheckout: () -> PlayCheckoutId?,
    ): PlayCheckoutResult
}

internal class GooglePlayMembershipBilling(context: Context) : PlayMembershipBilling {
    private val events = Channel<PlayBillingUpdate>(Channel.BUFFERED)
    override val updates = events.receiveAsFlow()
    private val connectionMutex = Mutex()
    @Volatile private var checkoutId: PlayCheckoutId? = null
    private val client = BillingClient.newBuilder(context.applicationContext)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener { result, purchases ->
            val observedCheckout = checkoutId
            events.trySend(PlayBillingUpdate(
                result = checkoutResult(result),
                purchases = purchases.orEmpty().mapNotNull(::membershipPurchase),
                checkoutId = observedCheckout,
            ))
        }
        .build()

    override suspend fun offers(plans: Set<PlayMembershipPlan>): List<PlayMembershipOffer> =
        queryOffers(plans).map { it.summary }

    override suspend fun purchases(): List<PlayMembershipPurchase> = queryPurchases(BillingClient.ProductType.INAPP)

    override suspend fun subscriptionPurchases(): List<PlayMembershipPurchase> = queryPurchases(BillingClient.ProductType.SUBS)

    private suspend fun queryPurchases(productType: String): List<PlayMembershipPurchase> {
        connect()
        if (productType == BillingClient.ProductType.SUBS && !supportsSubscriptions()) return emptyList()
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(productType)
            .includeSuspendedSubscriptions(productType == BillingClient.ProductType.SUBS)
            .build()
        val (result, purchases) = awaitBilling<Pair<BillingResult, List<Purchase>>> { finished ->
            client.queryPurchasesAsync(params) { result, purchases -> finished(result to purchases) }
        }
        requireSuccess(result)
        return purchases.mapNotNull(::membershipPurchase)
    }

    override suspend fun launch(
        activity: ComponentActivity,
        offer: PlayMembershipOffer,
        accountMarker: String,
        replacesPurchaseToken: String?,
        beginCheckout: () -> PlayCheckoutId?,
    ): PlayCheckoutResult {
        val fresh = queryOffers(setOf(offer.plan)).singleOrNull()
        if (fresh?.summary != offer) return PlayCheckoutResult.PRICE_CHANGED

        val product = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(fresh.details)
            .setOfferToken(fresh.offerToken)
        if (replacesPurchaseToken != null) {
            if (offer.plan.basePlanId == null || offer.plan.productId != SUBSCRIPTION_PRODUCT_ID) return PlayCheckoutResult.FAILED
            product.setSubscriptionProductReplacementParams(
                SubscriptionProductReplacementParams.newBuilder()
                    .setOldProductId(SUBSCRIPTION_PRODUCT_ID)
                    .setReplacementMode(SubscriptionProductReplacementParams.ReplacementMode.WITHOUT_PRORATION)
                    .build(),
            )
        }
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(product.build()))
            .setObfuscatedAccountId(accountMarker)
        if (replacesPurchaseToken != null) {
            flow.setSubscriptionUpdateParams(
                BillingFlowParams.SubscriptionUpdateParams.newBuilder().setOldPurchaseToken(replacesPurchaseToken).build(),
            )
        }
        val params = flow.build()
        if (activity.isFinishing || activity.isDestroyed ||
            !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) return PlayCheckoutResult.CANCELLED
        // Stamp callbacks before Play can deliver anything for this launch.
        checkoutId = beginCheckout() ?: return PlayCheckoutResult.CANCELLED
        return checkoutResult(client.launchBillingFlow(activity, params))
    }

    private data class StoreOffer(
        val summary: PlayMembershipOffer,
        val details: ProductDetails,
        val offerToken: String,
    )

    private suspend fun queryOffers(plans: Set<PlayMembershipPlan>): List<StoreOffer> {
        val firstPlan = plans.firstOrNull() ?: return emptyList()
        val recurring = firstPlan.basePlanId != null
        require(plans.all { (it.basePlanId != null) == recurring })
        connect()
        if (recurring && !supportsSubscriptions()) return emptyList()
        val type = if (recurring) BillingClient.ProductType.SUBS else BillingClient.ProductType.INAPP
        val products = queryProducts(plans.map(PlayMembershipPlan::productId).distinct(), type)
        return plans.mapNotNull { plan ->
            val product = products.singleOrNull { it.productId == plan.productId && it.productType == type }
                ?: return@mapNotNull null
            if (recurring) subscriptionOffer(plan, product) else oneTimeOffer(plan, product)
        }
    }

    private suspend fun queryProducts(productIds: List<String>, type: String): List<ProductDetails> {
        val productList = productIds.map { id ->
            QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        val (result, products) = awaitBilling<Pair<BillingResult, List<ProductDetails>>> { finished ->
            client.queryProductDetailsAsync(params) { result, response -> finished(result to response.productDetailsList) }
        }
        requireSuccess(result)
        return products
    }

    private fun oneTimeOffer(plan: PlayMembershipPlan, product: ProductDetails): StoreOffer? {
        val option = product.oneTimePurchaseOfferDetailsList.orEmpty().singleOrNull {
            it.purchaseOptionId == PlayMembershipPlan.PURCHASE_OPTION && it.offerId.isNullOrEmpty() &&
                it.preorderDetails == null && it.rentalDetails == null
        } ?: return null
        val token = option.offerToken?.takeIf { it.isNotBlank() } ?: return null
        return StoreOffer(
            PlayMembershipOffer(plan, option.formattedPrice, option.priceAmountMicros, option.priceCurrencyCode),
            product,
            token,
        )
    }

    private fun subscriptionOffer(plan: PlayMembershipPlan, product: ProductDetails): StoreOffer? {
        val option = product.subscriptionOfferDetails.orEmpty().singleOrNull {
            it.basePlanId == plan.basePlanId && it.offerId.isNullOrEmpty() && it.installmentPlanDetails == null
        } ?: return null
        val phase = option.pricingPhases.pricingPhaseList.singleOrNull() ?: return null
        val period = if (plan.cycle == "month") "P1M" else "P1Y"
        if (phase.recurrenceMode != ProductDetails.RecurrenceMode.INFINITE_RECURRING ||
            phase.billingPeriod != period || phase.priceAmountMicros <= 0L || option.offerToken.isBlank()
        ) {
            return null
        }
        return StoreOffer(
            PlayMembershipOffer(plan, phase.formattedPrice, phase.priceAmountMicros, phase.priceCurrencyCode),
            product,
            option.offerToken,
        )
    }

    private fun supportsSubscriptions(): Boolean {
        val result = client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        if (result.responseCode == BillingResponseCode.FEATURE_NOT_SUPPORTED) return false
        requireSuccess(result)
        return true
    }

    private suspend fun connect() = connectionMutex.withLock {
        if (client.isReady) return@withLock
        val result = awaitBilling<BillingResult> { finished ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) = finished(result)
                override fun onBillingServiceDisconnected() = Unit
            })
        }
        requireSuccess(result)
    }

    private fun requireSuccess(result: BillingResult) {
        when (result.responseCode) {
            BillingResponseCode.OK -> Unit
            BillingResponseCode.ERROR, BillingResponseCode.NETWORK_ERROR,
            BillingResponseCode.SERVICE_UNAVAILABLE, BillingResponseCode.SERVICE_DISCONNECTED ->
                throw PlayBillingUnavailable()
            else -> throw PlayBillingUnavailable(retryable = false)
        }
    }

    private fun membershipPurchase(purchase: Purchase): PlayMembershipPurchase? {
        if (purchase.packageName != PlayLedger.PACKAGE || purchase.quantity != 1) return null
        val productId = purchase.products.singleOrNull()?.takeIf { id ->
            id == SUBSCRIPTION_PRODUCT_ID || PlayMembershipPlan.entries.any { it.productId == id }
        } ?: return null
        val pending = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> false
            Purchase.PurchaseState.PENDING -> true
            else -> return null
        }
        return PlayMembershipPurchase(
            productId,
            purchase.accountIdentifiers?.obfuscatedAccountId,
            purchase.purchaseToken,
            pending,
            suspended = productId == SUBSCRIPTION_PRODUCT_ID && purchase.isSuspended,
            acknowledged = purchase.isAcknowledged,
        )
    }

    private fun checkoutResult(result: BillingResult): PlayCheckoutResult = when (result.responseCode) {
        BillingResponseCode.OK -> PlayCheckoutResult.STARTED
        BillingResponseCode.USER_CANCELED -> PlayCheckoutResult.CANCELLED
        BillingResponseCode.ITEM_ALREADY_OWNED -> PlayCheckoutResult.ALREADY_OWNED
        else -> PlayCheckoutResult.FAILED
    }

    private suspend fun <T : Any> awaitBilling(request: ((T) -> Unit) -> Unit): T =
        withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { continuation ->
                request { value -> if (continuation.isActive) continuation.resume(value) }
            }
        } ?: throw PlayBillingUnavailable()
}
