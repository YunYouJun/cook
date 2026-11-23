package cn.yunyoujun.cook.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.security.MessageDigest

internal enum class PlayLedger(val wireValue: String, val endpoint: String) {
    PRODUCTION("Production", "cook-play-order"),
    SANDBOX("Sandbox", "cook-play-order-sandbox");

    companion object {
        const val PACKAGE = "fun.yunle.cook"
    }
}

internal enum class PlayMembershipPlan(
    val productId: String,
    val cycle: String,
    val title: String,
    val basePlanId: String? = null,
) {
    MONTH("cook_member_month", "month", "单次月付"),
    YEAR("cook_member_year", "year", "单次年付"),
    MONTHLY_SUBSCRIPTION(SUBSCRIPTION_PRODUCT_ID, "month", "自动续费月付", "monthly"),
    YEARLY_SUBSCRIPTION(SUBSCRIPTION_PRODUCT_ID, "year", "自动续费年付", "yearly");

    companion object {
        const val PURCHASE_OPTION = "buy"
    }
}

internal const val SUBSCRIPTION_PRODUCT_ID = "cook_member_subscription"

internal data class PlayMembershipCapabilities(val accountMarker: String, val products: Set<PlayMembershipPlan>)

internal enum class PlaySettlement(val wireValue: String) {
    PENDING("pending"),
    CANCELLED("cancelled"),
    COMMITTED("committed"),
    CONFIRMED("confirmed"),
}

internal enum class PlaySubscriptionState(val wireValue: String, val label: String) {
    PENDING("pending", "等待付款"),
    ACTIVE("active", "订阅有效"),
    GRACE("grace", "付款宽限期"),
    HOLD("hold", "付款待处理"),
    PAUSED("paused", "订阅已暂停"),
    CANCELLED("cancelled", "已关闭续订"),
    EXPIRED("expired", "订阅已到期"),
}

internal data class PlaySubscriptionStatus(
    val plan: PlayMembershipPlan,
    val state: PlaySubscriptionState,
    val autoRenewing: Boolean,
    val periodExpiresAt: Long?,
)

internal data class PlaySubscriptionResult(val settlement: PlaySettlement, val subscription: PlaySubscriptionStatus)

internal class PlayMembershipUnavailable(val retryable: Boolean = false) : Exception()

internal class PlayMembershipService(
    val ledger: PlayLedger,
    private val transport: HttpTransport = IsolatedHttpPostTransport,
) {
    private val markerFormat = Regex("[0-9a-f]{64}")

    suspend fun capabilities(credential: YunlefunCredential): PlayMembershipCapabilities {
        val body = request("getCapabilities", credential)
        val marker = body.string("accountMarker")?.takeIf(markerFormat::matches) ?: invalid()
        val payload = body["products"] as? JsonArray ?: invalid()
        val subscriptions = when (val value = body["subscriptions"]) {
            null -> JsonArray(emptyList())
            is JsonArray -> value
            else -> invalid()
        }
        if (payload.size + subscriptions.size > PlayMembershipPlan.entries.size) invalid()
        val products = payload.map { entry ->
            val product = entry as? JsonObject ?: invalid()
            PlayMembershipPlan.entries.firstOrNull { plan ->
                plan.basePlanId == null && product.string("id") == plan.productId && product.string("cycle") == plan.cycle &&
                    product.string("purchaseOptionId") == PlayMembershipPlan.PURCHASE_OPTION
            } ?: invalid()
        }.plus(subscriptions.map { entry -> readSubscriptionPlan(entry as? JsonObject ?: invalid()) }).toSet()
        if (products.size != payload.size + subscriptions.size) invalid()
        return PlayMembershipCapabilities(marker, products)
    }

    suspend fun preparePurchase(
        credential: YunlefunCredential,
        plan: PlayMembershipPlan,
        replacesPurchaseToken: String? = null,
    ): String {
        require(replacesPurchaseToken == null || plan.basePlanId != null)
        replacesPurchaseToken?.let(::requirePurchaseToken)
        val body = request(
            "preparePurchase", credential, productId = plan.productId, basePlanId = plan.basePlanId,
            replacesPurchaseToken = replacesPurchaseToken,
        )
        if (body.string("productId") != plan.productId || body.string("basePlanId") != plan.basePlanId) invalid()
        if (replacesPurchaseToken != null && body.string("replacementMode") != "WITHOUT_PRORATION") invalid()
        return body.string("accountMarker")?.takeIf(markerFormat::matches) ?: invalid()
    }

    suspend fun settle(
        credential: YunlefunCredential,
        plan: PlayMembershipPlan,
        token: String,
    ): PlaySettlement {
        require(plan.basePlanId == null)
        return readSettlement(purchaseReceipt("verifyPurchase", credential, plan.productId, token))
    }

    suspend fun verifySubscription(credential: YunlefunCredential, token: String): PlaySubscriptionResult {
        val body = purchaseReceipt("verifySubscription", credential, SUBSCRIPTION_PRODUCT_ID, token)
        val plan = readSubscriptionPlan(body, idKey = "productId")
        val state = PlaySubscriptionState.entries.firstOrNull { it.wireValue == body.string("state") } ?: invalid()
        val autoRenewing = (body["autoRenewing"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
        val expiry = when (val value = body["periodExpiresAt"]) {
            JsonNull -> null
            is JsonPrimitive -> value.takeUnless { it.isString }?.longOrNull?.takeIf { it > 0 } ?: invalid()
            else -> invalid()
        }
        return PlaySubscriptionResult(readSettlement(body), PlaySubscriptionStatus(plan, state, autoRenewing, expiry))
    }

    private suspend fun purchaseReceipt(action: String, credential: YunlefunCredential, productId: String, token: String): JsonObject {
        requirePurchaseToken(token)
        val body = request(action, credential, token)
        val digest = MessageDigest.getInstance("SHA-256").digest(token.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        if (body.string("productId") != productId || body.string("purchaseReference") != digest) invalid()
        return body
    }

    private fun requirePurchaseToken(token: String) {
        require(token.length in 16..2048 && token.all { it in '!'..'~' })
    }

    private fun readSettlement(body: JsonObject): PlaySettlement =
        PlaySettlement.entries.firstOrNull { body.string("status") == it.wireValue } ?: invalid()

    private fun readSubscriptionPlan(value: JsonObject, idKey: String = "id"): PlayMembershipPlan =
        PlayMembershipPlan.entries.firstOrNull { plan ->
            plan.basePlanId != null && value.string(idKey) == plan.productId && value.string("basePlanId") == plan.basePlanId &&
                value.string("cycle") == plan.cycle
        } ?: invalid()

    suspend fun sandboxEntitlement(credential: YunlefunCredential): MembershipEntitlement {
        check(ledger == PlayLedger.SANDBOX)
        return readMembershipEntitlement(credential.subject, request("getEntitlement", credential))
    }

    private suspend fun request(
        action: String,
        credential: YunlefunCredential,
        token: String? = null,
        productId: String? = null,
        basePlanId: String? = null,
        replacesPurchaseToken: String? = null,
    ): JsonObject {
        if (!credential.isValid()) throw YunlefunAccountException.ExpiredSession()
        val response = transport.request(
            method = "POST",
            url = MembershipService.FUNCTIONS_GATEWAY + ledger.endpoint,
            headers = mapOf("Authorization" to "Bearer ${credential.accessToken}"),
            jsonBody = buildJsonObject {
                put("action", action)
                if (token != null) put("purchaseToken", token)
                if (productId != null) put("productId", productId)
                if (basePlanId != null) put("basePlanId", basePlanId)
                if (replacesPurchaseToken != null) put("replacesPurchaseToken", replacesPurchaseToken)
            }.toString(),
            maxResponseBytes = 16 * 1024,
            readTimeoutMs = 35_000,
        )
        if (response.statusCode == 401) throw YunlefunAccountException.ExpiredSession()
        if (response.statusCode !in 200..299) {
            val status = response.statusCode
            throw PlayMembershipUnavailable(retryable = status == 408 || status == 429 || status in 500..599)
        }
        val body = runCatching { Json.parseToJsonElement(response.bodyText) as? JsonObject }.getOrNull() ?: invalid()
        val code = body.string("code")
        if (code == "login_required") throw YunlefunAccountException.ExpiredSession()
        if (body["code"] != null) throw PlayMembershipUnavailable(retryable = code == "play_unavailable")
        if (body.string("application") != "cook" || body.string("packageName") != PlayLedger.PACKAGE ||
            body.string("ledgerEnvironment") != ledger.wireValue
        ) invalid()
        return body
    }

    private fun JsonObject.string(key: String): String? =
        (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun invalid(): Nothing = throw YunlefunAccountException.InvalidResponse()
}
