package cn.yunyoujun.cook.data.local

import android.content.Context
import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val MAX_PACK_BYTES = 8 * 1024 * 1024
private const val SHOWCASE_ROOT = "https://assets.yunle.fun/cook/showcase"
private const val PACK_RELOAD_ERROR = "无法读取离线素材，请重试。"
private val pairPattern = Regex("[a-z0-9]+(-[a-z0-9]+)*")
private val digestPattern = Regex("[0-9a-f]{64}")

@Serializable
internal enum class ShowcaseKind(val path: String, val extension: String) {
    @SerialName("image") Image("image", "webp"),
    @SerialName("model") Model("model", "glb"),
}

@Serializable
internal data class ShowcaseAsset(
    val kind: ShowcaseKind,
    val version: Int,
    val url: String,
    val bytes: Int,
    val sha256: String,
) {
    val filename: String get() = "v$version-$sha256.${kind.extension}"

    fun isValid(recipeId: Int, pairId: String): Boolean =
        version > 0 && bytes in 1..MAX_PACK_BYTES && digestPattern.matches(sha256) &&
            url == "$SHOWCASE_ROOT/$recipeId/$pairId/${kind.path}/$filename"
}

@Serializable
private data class ShowcaseManifest(
    @SerialName("recipeID") val recipeId: Int,
    @SerialName("pairID") val pairId: String,
    val packs: List<ShowcaseAsset>,
    val androidModel: ShowcaseAsset? = null,
)

internal class ShowcaseAssetLibrary(
    manifestJson: String,
    root: File,
    scope: CoroutineScope,
    transfer: ShowcaseTransfer = HttpShowcaseTransfer(),
    openBundledAsset: ((String) -> InputStream)? = null,
) {
    val dishes: Map<Int, List<ShowcasePackStore>> = runCatching {
        AtomicJsonFiles.json.decodeFromString<List<ShowcaseManifest>>(manifestJson)
    }.getOrDefault(emptyList()).let { entries ->
        val recipes = entries.groupingBy { it.recipeId }.eachCount()
        val pairs = entries.groupingBy { it.pairId }.eachCount()
        buildMap {
            for (entry in entries) {
                val (recipeId, pairId) = entry
                if (recipeId <= 0 || !pairPattern.matches(pairId) || recipes[recipeId] != 1 || pairs[pairId] != 1) continue
                val image = entry.packs.singleOrNull { it.kind == ShowcaseKind.Image } ?: continue
                if (!image.isValid(recipeId, pairId)) continue
                val model = entry.androidModel?.takeIf { it.kind == ShowcaseKind.Model && it.isValid(recipeId, pairId) }
                put(recipeId, listOfNotNull(image, model).map { asset ->
                    val folder = File(root, "$recipeId-$pairId/${asset.kind.path}")
                    val bundledModel = openBundledAsset?.takeIf { asset.kind == ShowcaseKind.Model }?.let { open ->
                        { open("cook/showcase/models/$recipeId-$pairId/${asset.filename}") }
                    }
                    ShowcasePackStore(asset, folder, scope, transfer, bundledModel)
                })
            }
        }
    }

    companion object {
        fun bundled(context: Context, scope: CoroutineScope): ShowcaseAssetLibrary {
            val assets = context.assets
            val manifest = runCatching {
                assets.open("cook/showcase-packs.json").bufferedReader().use { it.readText() }
            }.getOrDefault("[]")
            return ShowcaseAssetLibrary(
                manifest,
                File(context.noBackupFilesDir, "showcase"),
                scope,
                openBundledAsset = { path -> assets.open(path) },
            )
        }
    }
}

internal data class ShowcaseInstallation(val file: File, val version: Int, val bytes: Int)

internal data class ShowcasePackState(
    val installation: ShowcaseInstallation? = null,
    val busy: Boolean = false,
    val receivedBytes: Int? = null,
    val error: String? = null,
    val installationChecked: Boolean = false,
)

internal class ShowcasePackStore(
    val asset: ShowcaseAsset,
    private val folder: File,
    private val scope: CoroutineScope,
    private val transfer: ShowcaseTransfer,
    private val openBundledModel: (() -> InputStream)? = null,
) {
    private val mutableState = MutableStateFlow(ShowcasePackState())
    val state = mutableState.asStateFlow()
    val hasBundledModel: Boolean get() = openBundledModel != null
    private var operation: Job? = null

    @MainThread
    fun reload(): Job? = runOperation(PACK_RELOAD_ERROR, clearError = false) {
        val active = withContext(Dispatchers.IO) { findInstallation() }
        mutableState.update { state ->
            state.copy(
                installation = active,
                installationChecked = true,
                error = state.error.takeUnless { it == PACK_RELOAD_ERROR },
            )
        }
    }

    @MainThread
    fun download(): Job? =
        runInstall("下载失败，请重试。") { request ->
            mutableState.update { it.copy(receivedBytes = 0) }
            withTimeout(120_000) {
                transfer.fetch(asset) { count ->
                    scope.launch {
                        if (operation === request && request.isActive) {
                            mutableState.update { it.copy(receivedBytes = count) }
                        }
                    }
                }
            }
        }

    @MainThread
    fun installBundled(): Job? {
        val open = openBundledModel ?: return null
        return runInstall("内置模型安装失败，请重试。") {
            try {
                open().use { BoundedIoStreams.readBytesBounded(it, asset.bytes) }
            } catch (error: BoundedIoStreams.TooLargeException) {
                throw IOException("Bundled model exceeds its manifest size", error)
            }
        }
    }

    @MainThread
    private fun runInstall(error: String, read: suspend (Job) -> ByteArray): Job? {
        if (state.value.installation?.file?.name == asset.filename) return null
        return runOperation(error) {
            val request = coroutineContext.job
            withContext(Dispatchers.IO) {
                val data = read(request)
                if (data.size != asset.bytes || digest(data) != asset.sha256) {
                    throw IOException("Showcase asset does not match its manifest")
                }
                ensureActive()
                // A verified copy is ready. Publish it as one filesystem operation before retiring the old copy.
                withContext(NonCancellable) {
                    val installed = install(data)
                    mutableState.update { it.copy(installation = installed, installationChecked = true) }
                }
            }
        }
    }

    @MainThread
    fun cancel() {
        if (state.value.receivedBytes != null) operation?.cancel()
    }

    @MainThread
    fun remove(): Job? = runOperation("删除失败，请重试。") {
        withContext(Dispatchers.IO) {
            if (!folder.deleteRecursively()) throw IOException("Cannot remove showcase pack")
        }
        mutableState.update { it.copy(installation = null, installationChecked = true) }
    }

    @MainThread
    private fun runOperation(error: String, clearError: Boolean = true, block: suspend () -> Unit): Job? {
        if (operation != null) return null
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            operation = coroutineContext.job
            mutableState.update { it.copy(busy = true, error = if (clearError) null else it.error) }
            try {
                block()
            } catch (_: TimeoutCancellationException) {
                mutableState.update { it.copy(error = error) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                mutableState.update { it.copy(error = error) }
            } finally {
                mutableState.update { it.copy(busy = false, receivedBytes = null) }
                operation = null
            }
        }
    }

    private fun findInstallation(): ShowcaseInstallation? {
        // A process can stop between writing a temporary file and publishing it.
        folder.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        val filenamePattern = Regex("v([1-9][0-9]*)-([0-9a-f]{64})\\.${asset.kind.extension}")
        return folder.listFiles().orEmpty().mapNotNull { file ->
            val match = filenamePattern.matchEntire(file.name) ?: return@mapNotNull null
            val version = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val length = file.length()
            if (!file.isFile || length !in 1..MAX_PACK_BYTES.toLong() ||
                version > asset.version || version == asset.version && (file.name != asset.filename || length != asset.bytes.toLong())
            ) return@mapNotNull null
            ShowcaseInstallation(file, version, length.toInt())
        }.sortedByDescending { it.version }.firstOrNull { installed ->
            runCatching {
                val destination = installed.file
                // Cache files are app-private and only written under this pack's operation lock.
                destination.inputStream().use { input ->
                    val expected = destination.nameWithoutExtension.substringAfter('-')
                    val data = BoundedIoStreams.readBytesBounded(input, installed.bytes)
                    data.size == installed.bytes && digest(data) == expected
                }
            }.getOrDefault(false)
        }
    }

    private fun install(data: ByteArray): ShowcaseInstallation {
        if (!folder.mkdirs() && !folder.isDirectory) throw IOException("Cannot create showcase folder")
        val destination = File(folder, asset.filename)
        val staging = File.createTempFile("showcase-", ".part", folder)
        try {
            FileOutputStream(staging).use { output ->
                output.write(data)
                output.fd.sync()
            }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            staging.delete()
        }
        folder.listFiles()?.filterNot { it == destination }?.forEach { it.delete() }
        return ShowcaseInstallation(destination, asset.version, asset.bytes)
    }
}

internal fun interface ShowcaseTransfer {
    suspend fun fetch(asset: ShowcaseAsset, progress: (Int) -> Unit): ByteArray
}

internal class HttpShowcaseTransfer(
    private val open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
) : ShowcaseTransfer {
    override suspend fun fetch(asset: ShowcaseAsset, progress: (Int) -> Unit): ByteArray =
        suspendCancellableCoroutine { result ->
            val connection = open(asset.url).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept-Encoding", "identity")
            }
            val io = Dispatchers.IO.asExecutor()
            result.invokeOnCancellation {
                // Clearing the operation must not wait for a blocked network read or disconnect.
                io.execute { runCatching { connection.disconnect() } }
            }
            io.execute {
                try {
                    if (!result.isActive) return@execute
                    if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                        connection.contentLengthLong.let { it != -1L && it != asset.bytes.toLong() }
                    ) throw IOException("Unexpected showcase response")
                    val data = ByteArray(asset.bytes)
                    connection.inputStream.use { input ->
                        var received = 0
                        var reported = 0
                        while (received < data.size) {
                            if (!result.isActive) return@execute
                            val read = input.read(data, received, minOf(16 * 1024, data.size - received))
                            if (read < 0) throw IOException("Incomplete showcase response")
                            received += read
                            if (received - reported >= maxOf(16 * 1024, data.size / 50) || received == data.size) {
                                if (!result.isActive) return@execute
                                progress(received)
                                reported = received
                            }
                        }
                        if (!result.isActive) return@execute
                        if (input.read() != -1) throw IOException("Showcase response exceeds expected size")
                    }
                    result.resume(data)
                } catch (error: Exception) {
                    if (result.isActive) result.resumeWithException(error)
                } finally {
                    runCatching { connection.disconnect() }
                }
            }
        }
}

private fun digest(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
