package cn.yunyoujun.cook.data.remote

import java.net.URI

internal data class RecipeContributionDraft(
    val name: String,
    val source: String,
    val details: String,
) {
    private val cleanName = name.trim()
    private val cleanSource = source.trim()

    val hasValidSource: Boolean = !cleanSource.any(Char::isWhitespace) &&
        runCatching { URI(cleanSource) }.getOrNull()?.let {
            it.scheme?.lowercase() in setOf("https", "http") && !it.host.isNullOrEmpty()
        } == true

    val note: String = buildString {
        append("菜谱投稿\n菜名：")
        append(cleanName)
        append("\n做法链接：")
        append(cleanSource)
        details.trim().takeIf { it.isNotEmpty() }?.let {
            append("\n补充说明：")
            append(it)
        }
    }

    val canSubmit: Boolean
        get() = cleanName.isNotEmpty() && hasValidSource && note.length <= FeedbackClient.MAX_FEEDBACK_NOTE
}
