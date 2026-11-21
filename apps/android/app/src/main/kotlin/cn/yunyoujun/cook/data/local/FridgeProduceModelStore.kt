package cn.yunyoujun.cook.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest

@Serializable
internal data class FridgeProduceModel(val file: String, val bytes: Long, val sha256: String) {
    init {
        require(bytes in 1L..64L * 1024 * 1024)
        require(sha256.matches(Regex("[0-9a-f]{64}")) && file == "classifier-$sha256.onnx")
    }
}

internal class FridgeProduceModelStore(
    private val directory: File,
    val model: FridgeProduceModel,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val installed = File(directory, "classifier.onnx")
    private val installation = Mutex()

    suspend fun installedModel(): File? = withContext(Dispatchers.IO) {
        installation.withLock {
            try {
                installed.takeIf(::matchesModel)
            } catch (_: IOException) {
                null
            }
        }
    }

    suspend fun download(): File = withContext(Dispatchers.IO) {
        installation.withLock {
            if (matchesModel(installed)) return@withLock installed
            Files.createDirectories(directory.toPath())
            val temporary = File(directory, "classifier.onnx.part")
            try {
                val url = URL("https://assets.yunle.fun/cook/android/${model.file}")
                val connection = connect(url).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = false
                    setRequestProperty("Accept-Encoding", "identity")
                }
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        throw IOException("Model download returned HTTP ${connection.responseCode}")
                    }
                    connection.inputStream.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var received = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > model.bytes) throw IOException("Model exceeds expected size")
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                if (!matchesModel(temporary)) throw IOException("Model size or SHA-256 does not match")
                ensureActive()
                Files.move(temporary.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                installed
            } finally {
                temporary.delete()
            }
        }
    }

    private fun matchesModel(file: File): Boolean {
        if (!file.isFile || file.length() != model.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream().buffered(), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) {
                // Reading through DigestInputStream updates the digest.
            }
        }
        return digest.digest().toHexString() == model.sha256
    }
}
