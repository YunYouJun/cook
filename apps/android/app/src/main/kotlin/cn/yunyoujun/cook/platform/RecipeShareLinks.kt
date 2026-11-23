package cn.yunyoujun.cook.platform

import android.content.Intent
import android.net.Uri
import java.net.URI

object RecipeShareLinks {
    internal const val HOST = "cook.yunle.fun"
    private const val PATH = "/recipes/share"
    private const val MAX_RECIPE_ID = 9_007_199_254_740_991L
    private const val MAX_RESTORABLE_URL_LENGTH = 8_192

    fun url(recipeId: Int): String = url(recipeId.toLong())

    private fun url(recipeId: Long): String = "https://$HOST$PATH/?id=$recipeId"

    // Links saved with activity state keep a compact canonical fallback for oversized input.
    internal fun browserUrl(id: Long, source: String?): String =
        source?.takeIf { it.length <= MAX_RESTORABLE_URL_LENGTH && recipeId(it) == id } ?: url(id)

    internal fun isSharePage(rawUrl: String): Boolean = sharePage(rawUrl) != null

    fun recipeId(rawUrl: String): Long? {
        val url = sharePage(rawUrl) ?: return null
        val idItems = url.rawQuery?.split('&')?.filter { Uri.decode(it.substringBefore('=')) == "id" }
        val value = idItems?.singleOrNull()?.substringAfter('=', "")?.let(Uri::decode) ?: return null
        return value.toLongOrNull()?.takeIf { it in 0..MAX_RECIPE_ID && it.toString() == value }
    }

    private fun sharePage(rawUrl: String): URI? {
        val url = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!url.scheme.equals("https", ignoreCase = true) ||
            !url.host.equals(HOST, ignoreCase = true) ||
            url.rawUserInfo != null || url.port !in listOf(-1, 443) ||
            (url.rawPath != PATH && url.rawPath != "$PATH/")
        ) return null
        return url
    }
}

internal fun browserLinkIntent(rawUrl: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(rawUrl)).apply {
        // Resolve to a browser even when the URL is associated with Cook.
        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    }
