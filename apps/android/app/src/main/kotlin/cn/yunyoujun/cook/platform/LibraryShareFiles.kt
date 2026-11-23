package cn.yunyoujun.cook.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LibraryShareFileProvider : FileProvider()

internal const val LIBRARY_EXPORT_FILENAME = "cook-user-data.json"

internal suspend fun libraryShareIntent(context: Context, json: String): Intent = withContext(Dispatchers.IO) {
    val directory = File(context.cacheDir, "library-shares").apply { mkdirs() }
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    val file = File.createTempFile("library-", ".json", directory)
    try {
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.library-shares", file, LIBRARY_EXPORT_FILENAME)
        Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, LIBRARY_EXPORT_FILENAME, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } catch (error: Exception) {
        file.delete()
        throw error
    }
}
