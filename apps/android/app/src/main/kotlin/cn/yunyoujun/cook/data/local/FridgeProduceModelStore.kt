package cn.yunyoujun.cook.data.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
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
    private val bundledModel: (() -> InputStream)? = null,
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val installed = File(directory, "classifier.onnx")
    private val partial = File(directory, "${model.file}.part")
    private val replacement = File(directory, "${model.file}.restart.part")
    private val bundledCandidate = File(directory, "${model.file}.bundled.part")
    private val installation = Mutex()

    sealed interface Status {
        data class Installed(val file: File) : Status
        data object Resumable : Status
        data object Missing : Status
    }

    suspend fun status(): Status = withContext(Dispatchers.IO) {
        installation.withLock {
            when {
                isVerified(installed) -> Status.Installed(installed)
                partial.isFile && (partial.length() in 1L until model.bytes || isVerified(partial)) -> Status.Resumable
                else -> Status.Missing
            }
        }
    }

    suspend fun installedModel(): File? = (status() as? Status.Installed)?.file

    suspend fun prepareOfflineModel(): File? = withContext(Dispatchers.IO) {
        installation.withLock {
            if (isVerified(installed)) return@withLock installed
            val openBundledModel = bundledModel ?: return@withLock null
            try {
                Files.createDirectories(directory.toPath())
                openBundledModel().use { input ->
                    FileOutputStream(bundledCandidate).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var remaining = model.bytes
                        while (remaining > 0) {
                            ensureActive()
                            val count = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                            if (count < 0) throw InvalidDownload("Bundled model is incomplete")
                            output.write(buffer, 0, count)
                            remaining -= count
                        }
                        if (input.read() != -1) throw InvalidDownload("Bundled model exceeds expected size")
                    }
                }
                if (!matchesModel(bundledCandidate)) throw InvalidDownload("Bundled model SHA-256 does not match")
                ensureActive()
                Files.move(bundledCandidate.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                partial.delete()
                installed
            } catch (_: IOException) {
                ensureActive()
                null
            } finally {
                bundledCandidate.delete()
            }
        }
    }

    suspend fun download(progress: (Long) -> Unit = {}): File = withContext(Dispatchers.IO) {
        installation.withLock {
            if (matchesModel(installed)) return@withLock installed
            Files.createDirectories(directory.toPath())
            directory.listFiles { file ->
                file.name != partial.name && file.name.startsWith("classifier") && file.name.endsWith(".part")
            }?.forEach(File::delete)
            try {
                val candidate = if (matchesModel(partial)) partial else {
                    if (partial.length() >= model.bytes) Files.deleteIfExists(partial.toPath())
                    val savedBytes = partial.length()
                    progress(savedBytes)
                    val received = try {
                        transfer(partial, savedBytes, progress)
                    } catch (e: RangeRejected) {
                        if (savedBytes == 0L) throw e
                        transfer(replacement, 0, progress)
                    }
                    if (received.length() < model.bytes) throw IOException("Model download is incomplete")
                    if (!matchesModel(received)) {
                        Files.deleteIfExists(received.toPath())
                        throw InvalidDownload("Model SHA-256 does not match")
                    }
                    received
                }
                ensureActive()
                Files.move(candidate.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                if (candidate == replacement) partial.delete()
                installed
            } finally {
                replacement.delete()
            }
        }
    }

    private suspend fun transfer(file: File, start: Long, progress: (Long) -> Unit): File = coroutineScope {
        val url = URL("https://assets.yunle.fun/cook/android/${model.file}")
        val connection = connect(url).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
            if (start > 0) setRequestProperty("Range", "bytes=$start-")
        }
        val disconnect = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                // The socket read may still be blocked after the waiting coroutine is cancelled.
                runCatching { connection.disconnect() }
            }
        }
        try {
            runInterruptible {
                val offset = when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> 0L
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val expected = "bytes $start-${model.bytes - 1}/${model.bytes}"
                        if (start == 0L || connection.getHeaderField("Content-Range") != expected) throw RangeRejected()
                        start
                    }
                    416 -> throw RangeRejected()
                    else -> throw IOException("Model download returned HTTP $status")
                }
                val length = connection.contentLengthLong
                val encoding = connection.contentEncoding
                if ((length != -1L && length != model.bytes - offset) ||
                    (encoding != null && !encoding.equals("identity", ignoreCase = true))
                ) throw InvalidDownload("Unexpected model response length or encoding")

                // Some servers send the entire file after a Range request. Keep the prefix until installation succeeds.
                val destination = if (start > 0 && offset == 0L) replacement else file
                try {
                    connection.inputStream.use { input ->
                        FileOutputStream(destination, offset > 0).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            val progressInterval = maxOf(buffer.size.toLong(), model.bytes / 100)
                            var received = offset
                            var reported = offset
                            progress(offset)
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw CancellationException()
                                val count = input.read(buffer)
                                if (count < 0) break
                                received += count
                                if (received > model.bytes) throw InvalidDownload("Model exceeds expected size")
                                output.write(buffer, 0, count)
                                if (received - reported >= progressInterval || received == model.bytes) {
                                    progress(received)
                                    reported = received
                                }
                            }
                        }
                    }
                } catch (error: InvalidDownload) {
                    if (offset > 0L) RandomAccessFile(destination, "rw").use { it.setLength(offset) }
                    else Files.deleteIfExists(destination.toPath())
                    throw error
                }
                destination
            }
        } catch (e: IOException) {
            ensureActive()
            throw e
        } finally {
            disconnect.cancel()
        }
    }

    private class RangeRejected : IOException("Model range was not accepted")
    private class InvalidDownload(message: String) : IOException(message)

    private fun isVerified(file: File): Boolean = try {
        matchesModel(file)
    } catch (_: IOException) {
        false
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
