package cn.yunyoujun.cook.data.local

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class PrivateVideoDraft(
    val video: PrivateRecipeVideo,
    val file: File,
)

sealed class PrivateVideoException(message: String) : Exception(message) {
    class Format : PrivateVideoException("请选择可播放的 MP4、MOV 或 M4V 视频。")
    class TooLarge : PrivateVideoException("视频超过 500 MB，请先剪短或压缩。")
    class Empty : PrivateVideoException("视频文件为空，未改变原有附件。")
    class Unavailable : PrivateVideoException("请在原 iOS 设备导出 iCloud 视频，再在这里编辑菜谱并选择视频文件。")
    class Missing : PrivateVideoException("视频尚未同步到这台设备，或已被移动、删除。请稍后重试，或在编辑中重新选择视频。")
    class OtherDevice : PrivateVideoException("这个视频只保存在原来的设备上。请在原设备导出或在这里重新选择视频。")
    class Corrupt : PrivateVideoException("视频无法播放，文件可能已损坏或编码不受支持。请重新选择视频。")
}

/**
 * 本地私人菜谱视频附件仓库，对齐 iOS `PrivateRecipeVideoStore.swift`：
 * - 校验扩展名 (`mp4`, `mov`, `m4v`)、500 MB 上限与非空字节
 * - 先暂存到 `Drafts/<uuid>`，确认保存菜谱时再安装到 `Files/<uuid>.<ext>`
 * - 校验 `deviceID` 与 `location`（遇到 iCloud 位置或外机 deviceID 给出明确指引）
 */
class PrivateRecipeVideoStore internal constructor(
    private val context: Context? = null,
    localRootDir: File? = null,
    explicitDeviceId: String? = null,
    private val inspectVideo: (File) -> Unit = ::requireVideoTrack,
) {
    private val localRoot: File = localRootDir
        ?: File(requireNotNull(context) { "Context or localRootDir required" }.applicationContext.filesDir, "PrivateRecipeVideos")
    val deviceId: String = explicitDeviceId ?: resolveOrCreateDeviceId()

    private val activeDrafts = HashSet<String>()
    private var cleanedDrafts = false

    private fun resolveOrCreateDeviceId(): String {
        val prefs = requireNotNull(context) { "Context or explicitDeviceId required" }
            .applicationContext
            .getSharedPreferences("cook_private_videos", Context.MODE_PRIVATE)
        val key = "privateRecipeVideos.deviceID"
        val saved = prefs.getString(key, null)
        if (saved != null && runCatching { UUID.fromString(saved) }.isSuccess) {
            return saved.lowercase()
        }
        val fresh = UUID.randomUUID().toString().lowercase()
        prefs.edit().putString(key, fresh).apply()
        return fresh
    }

    @Synchronized
    fun removeAbandonedDrafts() {
        if (cleanedDrafts) return
        cleanedDrafts = true
        val draftsRoot = File(localRoot, "Drafts")
        val children = draftsRoot.listFiles().orEmpty()
        for (dir in children) {
            val name = dir.name
            if (runCatching { UUID.fromString(name) }.isSuccess && name !in activeDrafts) {
                dir.deleteRecursively()
            }
        }
        File(localRoot, "Files").listFiles { file ->
            file.isFile && file.name.startsWith(".video-") && file.name.endsWith(".part")
        }?.forEach { it.delete() }
    }

    suspend fun stageFromUri(uri: Uri, displayName: String? = null): PrivateVideoDraft = stageVideo {
        val ctx = requireNotNull(context) { "Context required for stageFromUri" }
        val (resolvedName, resolvedExt) = resolveUriNameAndExtension(ctx, uri, displayName)
        if (resolvedExt !in PrivateRecipeVideo.ALLOWED_EXTENSIONS) {
            throw PrivateVideoException.Format()
        }
        val input = ctx.contentResolver.openInputStream(uri)
            ?: throw PrivateVideoException.Format()
        input.use { stream ->
            stageFromStream(stream, originalName = resolvedName, extension = resolvedExt)
        }
    }

    private fun resolveUriNameAndExtension(
        ctx: Context,
        uri: Uri,
        explicitDisplayName: String?,
    ): Pair<String, String> {
        val queriedName = runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()

        val rawName = listOfNotNull(
            explicitDisplayName?.takeIf { it.isNotBlank() },
            queriedName?.takeIf { it.isNotBlank() },
            uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
        ).firstOrNull().orEmpty()

        val nameExt = rawName.substringAfterLast('.', "").lowercase()
        if (nameExt in PrivateRecipeVideo.ALLOWED_EXTENSIONS) {
            return rawName to nameExt
        }

        val mimeType = runCatching { ctx.contentResolver.getType(uri)?.lowercase() }.getOrNull()
        val mimeExt = when (mimeType) {
            "video/mp4" -> "mp4"
            "video/quicktime" -> "mov"
            "video/x-m4v", "video/m4v" -> "m4v"
            else -> mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it)?.lowercase() }.orEmpty()
        }
        val finalName = if (rawName.isNotBlank()) rawName else "video.${mimeExt.ifEmpty { "mp4" }}"
        return finalName to mimeExt
    }

    suspend fun stageFromFile(sourceFile: File): PrivateVideoDraft = stageVideo {
        if (!sourceFile.isFile) throw PrivateVideoException.Format()
        val ext = sourceFile.extension.lowercase()
        if (ext !in PrivateRecipeVideo.ALLOWED_EXTENSIONS) throw PrivateVideoException.Format()
        if (sourceFile.length() > PrivateRecipeVideo.BYTE_LIMIT) throw PrivateVideoException.TooLarge()
        sourceFile.inputStream().use { stream ->
            stageFromStream(stream, originalName = sourceFile.name.take(200), extension = ext)
        }
    }

    internal suspend fun stageFromBackup(input: InputStream, source: PrivateRecipeVideo): PrivateVideoDraft =
        stageVideo {
            stageFromStream(input, source.originalName, source.fileExtension, expectedBytes = source.byteCount)
        }

    private suspend fun stageVideo(read: suspend () -> PrivateVideoDraft): PrivateVideoDraft {
        var staged: PrivateVideoDraft? = null
        try {
            return withContext(Dispatchers.IO) { read().also { staged = it } }
        } catch (cancelled: CancellationException) {
            // Cancellation can arrive while returning a successfully copied file to the caller.
            withContext(NonCancellable + Dispatchers.IO) { staged?.let { discard(it) } }
            throw cancelled
        }
    }

    private suspend fun stageFromStream(
        input: InputStream,
        originalName: String,
        extension: String,
        expectedBytes: Long? = null,
    ): PrivateVideoDraft {
        if (extension !in PrivateRecipeVideo.ALLOWED_EXTENSIONS) {
            throw PrivateVideoException.Format()
        }
        val id = UUID.randomUUID().toString().lowercase()
        val draftDir = File(File(localRoot, "Drafts"), id)
        draftDir.mkdirs()
        val destination = File(draftDir, "$id.$extension")
        synchronized(this@PrivateRecipeVideoStore) {
            activeDrafts.add(id)
        }
        var keep = false
        try {
            val count = destination.outputStream().use {
                copyVideo(input, it, expectedBytes ?: PrivateRecipeVideo.BYTE_LIMIT)
            }
            if (count == 0L) throw PrivateVideoException.Empty()
            if (expectedBytes != null && count != expectedBytes) throw PrivateVideoException.Corrupt()
            inspectVideo(destination)
            coroutineContext.ensureActive()
            val video = PrivateRecipeVideo(
                id = id,
                fileExtension = extension,
                originalName = originalName.take(200).ifEmpty { "$id.$extension" },
                byteCount = count,
                location = PrivateRecipeVideo.Location.LOCAL,
                deviceID = deviceId,
            ).validated()
            keep = true
            return PrivateVideoDraft(video = video, file = destination)
        } finally {
            if (!keep) {
                synchronized(this@PrivateRecipeVideoStore) {
                    activeDrafts.remove(id)
                }
                draftDir.deleteRecursively()
            }
        }
    }

    @Synchronized
    fun discard(draft: PrivateVideoDraft) {
        val expectedDir = File(File(localRoot, "Drafts"), draft.video.id).canonicalFile
        if (draft.file.parentFile?.canonicalFile != expectedDir) return
        activeDrafts.remove(draft.video.id)
        expectedDir.deleteRecursively()
    }

    fun install(draft: PrivateVideoDraft): PrivateRecipeVideo =
        installInternal(draft, discardDraft = true)

    suspend fun installWithoutDiscarding(draft: PrivateVideoDraft): PrivateRecipeVideo =
        withContext(Dispatchers.IO) {
            installInternal(draft, discardDraft = false)
        }

    private fun installInternal(draft: PrivateVideoDraft, discardDraft: Boolean): PrivateRecipeVideo {
        val validated = draft.video.validated()
        val freshId = UUID.randomUUID().toString().lowercase()
        val installed = validated.copy(
            id = freshId,
            location = PrivateRecipeVideo.Location.LOCAL,
            deviceID = deviceId,
        ).validated()
        val filesRoot = File(localRoot, "Files")
        filesRoot.mkdirs()
        val destination = File(filesRoot, installed.fileName)
        val temporary = Files.createTempFile(filesRoot.toPath(), ".video-", ".part").toFile()
        try {
            draft.file.copyTo(temporary, overwrite = true)
            if (temporary.length() != installed.byteCount) throw PrivateVideoException.Corrupt()
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: UnsupportedOperationException) {
                Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
        if (discardDraft) {
            discard(draft)
        }
        return installed
    }

    fun playableFile(video: PrivateRecipeVideo): File {
        val validated = runCatching { video.validated() }.getOrElse {
            throw PrivateVideoException.Format()
        }
        if (validated.location == PrivateRecipeVideo.Location.ICLOUD) {
            throw PrivateVideoException.Unavailable()
        }
        if (validated.deviceID != deviceId) {
            throw PrivateVideoException.OtherDevice()
        }
        val file = File(File(localRoot, "Files"), validated.fileName)
        if (!file.isFile) {
            throw PrivateVideoException.Missing()
        }
        if (file.length() <= 0L) {
            throw PrivateVideoException.Corrupt()
        }
        return file
    }

    /** Writes an independent file only to the document created by the system picker. */
    suspend fun exportToDocument(video: PrivateRecipeVideo?, uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = requireNotNull(context).contentResolver
        var completed = false
        try {
            requireNotNull(resolver.openOutputStream(uri, "wt")).use { output ->
                writeVideo(video ?: throw PrivateVideoException.Missing(), output)
            }
            completed = true
        } finally {
            if (!completed) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }

    internal suspend fun writeVideo(video: PrivateRecipeVideo, output: OutputStream) {
        val file = exportableFile(video)
        val count = file.inputStream().use { copyVideo(it, output, video.byteCount) }
        if (count != video.byteCount) throw PrivateVideoException.Corrupt()
    }

    internal fun exportableFile(video: PrivateRecipeVideo): File = playableFile(video).also {
        if (it.length() != video.byteCount) throw PrivateVideoException.Corrupt()
    }

    private suspend fun copyVideo(input: InputStream, output: OutputStream, byteLimit: Long = PrivateRecipeVideo.BYTE_LIMIT): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read == -1) return count
            count += read
            if (count > byteLimit) throw PrivateVideoException.TooLarge()
            output.write(buffer, 0, read)
        }
    }

    fun remove(video: PrivateRecipeVideo): Boolean {
        val validated = runCatching { video.validated() }.getOrNull() ?: return false
        if (validated.location != PrivateRecipeVideo.Location.LOCAL || validated.deviceID != deviceId) return true
        val file = File(File(localRoot, "Files"), validated.fileName)
        return runCatching { !file.exists() || (file.isFile && file.delete()) }.getOrDefault(false)
    }
}

private fun requireVideoTrack(file: File) {
    val extractor = MediaExtractor()
    val supported = try {
        extractor.setDataSource(file.absolutePath)
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        (0 until extractor.trackCount).any { index ->
            val format = extractor.getTrackFormat(index)
            format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true &&
                codecs.findDecoderForFormat(format) != null
        }
    } catch (_: Exception) {
        false
    } finally {
        extractor.release()
    }
    if (!supported) throw PrivateVideoException.Format()
}
