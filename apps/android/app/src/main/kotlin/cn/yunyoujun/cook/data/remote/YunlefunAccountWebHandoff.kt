package cn.yunyoujun.cook.data.remote

import android.os.SystemClock

enum class AccountWebsitePurpose {
    ACCOUNT_SETTINGS,
    MEMBERSHIP_PURCHASE,
}

internal class YunlefunAccountWebHandoff(
    private val startedAtMs: Long = SystemClock.elapsedRealtime(),
    private val verifier: String = YunlefunSSO.randomToken(),
) {
    private val request = YunlefunSSO.sha256Base64Url(verifier.toByteArray(Charsets.UTF_8))

    val startUrl: String
        get() = "$ENDPOINT?request=$request&transport=browser"

    fun complete(callback: String, nowMs: Long = SystemClock.elapsedRealtime()): Completion {
        if (!isCallback(callback) || nowMs - startedAtMs !in 0 until LIFETIME_MS) {
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
            url = "${YunlefunSSO.ISSUER}/auth/cook-account?request=$request&binding=$binding",
        )
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
