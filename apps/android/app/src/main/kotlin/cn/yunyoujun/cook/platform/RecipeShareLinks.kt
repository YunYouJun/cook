package cn.yunyoujun.cook.platform

import android.net.Uri
import java.net.URI

object RecipeShareLinks {
    private const val HOST = "cook.yunle.fun"
    private const val PATH = "/recipes/share"
    private const val MAX_RECIPE_ID = 9_007_199_254_740_991L

    fun url(recipeId: Int): String = "https://$HOST$PATH/?id=$recipeId"

    fun recipeId(rawUrl: String): Long? {
        val url = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        if (!url.scheme.equals("https", ignoreCase = true) ||
            !url.host.equals(HOST, ignoreCase = true) ||
            url.rawUserInfo != null || url.port !in listOf(-1, 443) ||
            (url.rawPath != PATH && url.rawPath != "$PATH/")
        ) return null

        val idItems = url.rawQuery?.split('&')?.filter { Uri.decode(it.substringBefore('=')) == "id" }
        val value = idItems?.singleOrNull()?.substringAfter('=', "")?.let(Uri::decode) ?: return null
        return value.toLongOrNull()?.takeIf { it in 0..MAX_RECIPE_ID && it.toString() == value }
    }
}
