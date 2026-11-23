package cn.yunyoujun.cook.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.data.local.PrivateRecipeVideoStore

class PrivateRecipeVideoShareProvider : FileProvider()

internal val PrivateRecipeVideo.exportMimeType: String
    get() = when (fileExtension) {
        "mov" -> "video/quicktime"
        "m4v" -> "video/x-m4v"
        else -> "video/mp4"
    }

internal val PrivateRecipeVideo.exportFilename: String
    get() {
        val name = originalName.substringAfterLast('/').substringAfterLast('\\')
        val stem = name.substringBeforeLast('.').trim().ifEmpty { "cook-video" }
        return "$stem.$fileExtension"
    }

internal fun privateRecipeVideoShareIntent(
    context: Context,
    videos: PrivateRecipeVideoStore,
    video: PrivateRecipeVideo,
): Intent {
    val file = videos.exportableFile(video)
    val name = video.exportFilename
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.private-recipe-video-shares", file, name)
    return Intent(Intent.ACTION_SEND).apply {
        type = video.exportMimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
