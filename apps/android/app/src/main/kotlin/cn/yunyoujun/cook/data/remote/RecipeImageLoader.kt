package cn.yunyoujun.cook.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

internal data class LoadedRecipeImage(val bitmap: Bitmap, val bytes: ByteArray, val mimeType: String) {
    val extension: String get() = if (mimeType == "image/jpeg") "jpg" else mimeType.substringAfter('/')
}

internal class RecipeImageLoader(
    private val fetch: (String) -> ByteArray = ::downloadRecipeImage,
) {
    private val cache = object : LruCache<String, LoadedRecipeImage>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LoadedRecipeImage) = value.bitmap.allocationByteCount + value.bytes.size
    }

    fun cached(url: String): LoadedRecipeImage? = cache.get(url)

    suspend fun load(url: String): LoadedRecipeImage = runInterruptible(Dispatchers.IO) {
        cache.get(url)?.let { return@runInterruptible it }
        val bytes = fetch(url)
        if (bytes.size > MaxRecipeImageBytes) throw IOException("Recipe image is too large")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val mimeType = bounds.outMimeType
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || mimeType !in supportedImageTypes) {
            throw IOException("Invalid recipe image")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 2048 || bounds.outHeight / inSampleSize > 2048) {
                inSampleSize *= 2
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Invalid recipe image")
        LoadedRecipeImage(bitmap, bytes, checkNotNull(mimeType)).also { cache.put(url, it) }
    }
}

private val supportedImageTypes = setOf("image/webp", "image/jpeg", "image/png")
private const val MaxRecipeImageBytes = 8 * 1024 * 1024

internal fun isRecipeImageUrl(value: String): Boolean = runCatching { URI(value) }.getOrNull()?.let {
    it.scheme == "https" && !it.host.isNullOrEmpty() && it.userInfo == null
} ?: false

private fun downloadRecipeImage(url: String): ByteArray {
    if (!isRecipeImageUrl(url)) throw IOException("Invalid recipe image URL")
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.apply {
        instanceFollowRedirects = false
        useCaches = false
        connectTimeout = 10_000
        readTimeout = 15_000
    }
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK || connection.contentLengthLong > MaxRecipeImageBytes) {
            throw IOException("Unexpected recipe image response")
        }
        return connection.inputStream.use { input ->
            val buffer = ByteArray(16 * 1024)
            val image = ByteArrayOutputStream()
            while (true) {
                if (Thread.currentThread().isInterrupted) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > MaxRecipeImageBytes - image.size()) throw IOException("Recipe image is too large")
                image.write(buffer, 0, count)
            }
            image.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}
