package cn.yunyoujun.cook.platform

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import cn.yunyoujun.cook.data.remote.LoadedRecipeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class RecipeImageFileProvider : FileProvider()

internal suspend fun recipeImageShareIntent(context: Context, name: String, image: LoadedRecipeImage): Intent =
    withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "recipe-image-shares").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val file = File.createTempFile("photo-", ".${image.extension}", directory)
        try {
            file.writeBytes(image.bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.recipe-images", file, name)
            Intent(Intent.ACTION_SEND).apply {
                type = image.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

@RequiresApi(29)
internal suspend fun saveRecipeImageToGallery(context: Context, name: String, image: LoadedRecipeImage) =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/云食册")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Cannot create image")
        try {
            writeRecipeImage(context, uri, image)
            val published = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            if (resolver.update(uri, published, null, null) == 0) throw IOException("Cannot publish image")
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

internal suspend fun writeRecipeImage(context: Context, uri: Uri, image: LoadedRecipeImage) = withContext(Dispatchers.IO) {
    val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open image destination")
    output.use { it.write(image.bytes) }
}
