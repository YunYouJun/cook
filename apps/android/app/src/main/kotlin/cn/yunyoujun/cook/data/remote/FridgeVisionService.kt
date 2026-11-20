package cn.yunyoujun.cook.data.remote

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException

enum class FridgeVisionProvider(val wireName: String, val recipient: String) {
    QWEN("qwen", "云乐坊（腾讯云 CloudBase）和阿里云百炼"),
    DEEPSEEK("deepseek", "云乐坊（腾讯云 CloudBase）和 DeepSeek"),
    CLOUDBASE("cloudbase", "云乐坊使用的腾讯云 CloudBase，由托管的 DeepSeek 模型识别"),
}

sealed class FridgeVisionException(message: String) : Exception(message) {
    class Photo : FridgeVisionException("无法处理这张照片，请换一张重试。")
    class Login : FridgeVisionException("请登录云乐坊后使用批量识图。")
    class Membership : FridgeVisionException("批量识图需要有效的云乐坊会员。")
    class ProviderChanged : FridgeVisionException("识图服务已更换，请核对新的照片接收方。")
    class RateLimited : FridgeVisionException("识图次数暂时已用完，请稍后再试。")
    class Unavailable : FridgeVisionException("批量识图暂时不可用，可以继续本机识别。")
    class InvalidResponse : FridgeVisionException("没有收到有效识别结果，请换图重试。")
    class Timeout : FridgeVisionException("识图超时，请重试。")
    class Offline : FridgeVisionException("连接识图服务失败，请检查网络。")
}

class FridgeVisionService(private val transport: HttpTransport = IsolatedHttpPostTransport) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun prepare(jpegSource: ByteArray): ByteArray = withContext(Dispatchers.Default) {
        val (summary, bitmap) = FridgePhotoInspector.decodeBoundedInMemory(jpegSource)
        try {
            encode(bitmap, summary.rotationDegrees)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun prepare(bitmap: Bitmap): ByteArray = encode(bitmap, rotation = 0)

    private suspend fun encode(bitmap: Bitmap, rotation: Int): ByteArray = withContext(Dispatchers.Default) {
        val longestEdge = maxOf(bitmap.width, bitmap.height)
        for ((edge, quality) in listOf(1600 to 80, 1280 to 76, 1024 to 72, 800 to 66, 640 to 58, 512 to 48)) {
            ensureActive()
            val scale = minOf(1f, edge.toFloat() / longestEdge)
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postRotate(rotation.toFloat())
            }
            val pixels = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            try {
                val output = ByteArrayOutputStream()
                if (pixels.compress(Bitmap.CompressFormat.JPEG, quality, output) && output.size() <= MAX_JPEG_BYTES) {
                    return@withContext output.toByteArray()
                }
            } finally {
                if (pixels !== bitmap) pixels.recycle()
            }
        }
        throw FridgeVisionException.Photo()
    }

    suspend fun memberProvider(credential: YunlefunCredential): FridgeVisionProvider {
        val response = response("GET", credential, null)
        if (response.member == false) throw FridgeVisionException.Membership()
        if (response.member != true) throw FridgeVisionException.InvalidResponse()
        return FridgeVisionProvider.entries.firstOrNull { it.wireName == response.provider }
            ?: throw FridgeVisionException.InvalidResponse()
    }

    suspend fun recognize(
        jpeg: ByteArray,
        provider: FridgeVisionProvider,
        credential: YunlefunCredential,
    ): List<String> {
        if (jpeg.size !in 4..MAX_JPEG_BYTES) throw FridgeVisionException.Photo()
        val body = json.encodeToString(
            VisionRequest(provider = provider.wireName, image = Base64.encodeToString(jpeg, Base64.NO_WRAP)),
        )
        val ingredients = response("POST", credential, body).ingredients
            ?: throw FridgeVisionException.InvalidResponse()
        if (ingredients.size > 20 || ingredients.distinct().size != ingredients.size || ingredients.any { !validName(it) }) {
            throw FridgeVisionException.InvalidResponse()
        }
        return ingredients
    }

    private suspend fun response(method: String, credential: YunlefunCredential, body: String?): VisionResponse {
        if (!credential.isValid() || credential.accessToken.length > 8192) throw FridgeVisionException.Login()
        val result = try {
            transport.request(
                method = method,
                url = ENDPOINT,
                headers = mapOf("Authorization" to "Bearer ${credential.accessToken}"),
                jsonBody = body,
                maxResponseBytes = 8192,
                connectTimeoutMs = 8000,
                readTimeoutMs = if (method == "GET") 15000 else 40000,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw FridgeVisionException.Timeout()
        } catch (_: IOException) {
            throw FridgeVisionException.Offline()
        } catch (_: Exception) {
            throw FridgeVisionException.Unavailable()
        }
        val wire = runCatching { json.decodeFromString<VisionResponse>(result.bodyText) }.getOrNull()
        return when (result.statusCode) {
            200 -> wire?.takeIf { it.ok && it.schemaVersion == 1 } ?: throw FridgeVisionException.InvalidResponse()
            400, 413 -> throw FridgeVisionException.Photo()
            401 -> throw FridgeVisionException.Login()
            403 -> if (wire?.code == "MEMBERSHIP_REQUIRED") throw FridgeVisionException.Membership()
                else throw FridgeVisionException.Unavailable()
            409 -> throw FridgeVisionException.ProviderChanged()
            429 -> throw FridgeVisionException.RateLimited()
            502 -> throw FridgeVisionException.InvalidResponse()
            504 -> throw FridgeVisionException.Timeout()
            else -> throw FridgeVisionException.Unavailable()
        }
    }

    @Serializable
    private data class VisionRequest(
        val provider: String,
        val image: String,
        val schemaVersion: Int = 1,
        val consentVersion: Int = 1,
    )

    @Serializable
    private data class VisionResponse(
        val ok: Boolean,
        val schemaVersion: Int? = null,
        val provider: String? = null,
        val member: Boolean? = null,
        val ingredients: List<String>? = null,
        val code: String? = null,
    )

    companion object {
        private const val ENDPOINT = "https://api.yunle.fun/cook-fridge-vision"
        private const val MAX_JPEG_BYTES = 70 * 1024

        fun validName(value: String): Boolean =
            value.length in 1..30 && value.any(Char::isLetterOrDigit) &&
                value.all { it.isLetterOrDigit() || it in " ·-（）()" }
    }
}
