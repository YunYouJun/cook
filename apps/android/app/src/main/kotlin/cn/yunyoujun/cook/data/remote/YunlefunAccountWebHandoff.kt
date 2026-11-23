package cn.yunyoujun.cook.data.remote

import android.os.SystemClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class AccountWebsitePurpose {
    ACCOUNT_SETTINGS,
    MEMBERSHIP_PURCHASE,
}

internal class AccountWebsiteLaunch(
    val session: MembershipSession,
    val purpose: AccountWebsitePurpose,
    val supportsHandoff: Boolean,
)

internal class ApprovedAccountWebsite(
    val url: String,
    val session: MembershipSession,
    val handoff: YunlefunAccountWebHandoff,
)

@Serializable
internal class YunlefunAccountWebHandoff(
    private val startedAtMs: Long,
    private val verifier: String,
) {
    constructor() : this(SystemClock.elapsedRealtime(), YunlefunSSO.randomToken())

    init {
        require(startedAtMs in 0..Long.MAX_VALUE - LIFETIME_MS && TOKEN.matches(verifier))
    }

    @Transient
    private val request = YunlefunSSO.sha256Base64Url(verifier.toByteArray(Charsets.UTF_8))

    val startUrl: String
        get() = "$ENDPOINT?request=$request&transport=browser"

    val expiresAtMs: Long get() = startedAtMs + LIFETIME_MS

    fun isCurrentAt(nowMs: Long): Boolean = nowMs in startedAtMs until expiresAtMs

    fun complete(callback: String, nowMs: Long = SystemClock.elapsedRealtime()): Completion {
        if (!isCallback(callback) || !isCurrentAt(nowMs)) {
            throw YunlefunAccountException.InvalidWebsiteCallback()
        }
        val result = callback.substringAfter('#').removePrefix(FRAGMENT_PREFIX)
        val binding = result.substringAfter('.', "")
        if (result.substringBefore('.') != request || !TOKEN.matches(binding)) {
            throw YunlefunAccountException.InvalidWebsiteCallback()
        }
        return Completion(
            approval = mapOf(
                "action" to "approveCookWebHandoff",
                "clientId" to YunlefunSSO.CLIENT_ID,
                "verifier" to verifier,
                "binding" to binding,
            ),
            url = continuationUrl(binding),
        )
    }

    fun continuationUrl(binding: String): String {
        if (!TOKEN.matches(binding)) throw YunlefunAccountException.InvalidWebsiteCallback()
        return "${YunlefunSSO.ISSUER}/auth/cook-account?request=$request&binding=$binding"
    }

    class Completion(val approval: Map<String, String>, val url: String)

    companion object {
        const val ENDPOINT = "${YunlefunSSO.ISSUER}/api/session/cook-handoff"
        const val SETTINGS_URL = "${YunlefunSSO.ISSUER}/settings?tab=account"
        const val LIFETIME_MS = 45_000L
        private const val FRAGMENT_PREFIX = "cook_handoff="
        private val TOKEN = Regex("[a-zA-Z0-9_-]{43}")

        fun isCallback(url: String): Boolean =
            YunlefunSSO.isCallback(url) && url.substringAfter('#', "").startsWith(FRAGMENT_PREFIX)
    }
}
