package cn.yunyoujun.cook.ui.state

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.data.local.DeviceBackupPreview
import cn.yunyoujun.cook.data.local.DeviceBackupSection
import cn.yunyoujun.cook.data.local.DeviceBackupStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class DeviceBackupViewModel(private val store: DeviceBackupStore) : ViewModel() {
    enum class FileAction { EXPORT, EXPORT_WITH_VIDEOS, IMPORT }

    private data class SelectedBackup(val uri: Uri, val preview: DeviceBackupPreview)

    private var pendingAction by mutableStateOf<FileAction?>(null)
    private var selected by mutableStateOf<SelectedBackup?>(null)
    val preview: DeviceBackupPreview? get() = selected?.preview
    var confirming by mutableStateOf<DeviceBackupSection?>(null)
        private set
    var restored by mutableStateOf(emptySet<DeviceBackupSection>())
        private set
    var working by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var hasError by mutableStateOf(false)
        private set
    val busy: Boolean get() = working || pendingAction != null

    fun chooseFile(action: FileAction, launch: () -> Unit) {
        if (busy) return
        pendingAction = action
        status = null
        hasError = false
        try {
            launch()
        } catch (_: Exception) {
            pendingAction = null
            showFailure("无法打开系统文件选择器。")
        }
    }

    fun fileReturned(action: FileAction, resolver: ContentResolver, uri: Uri?) {
        if (pendingAction != action) {
            if (uri != null && !busy) showFailure("文件选择已失效，请重新选择。")
            return
        }
        pendingAction = null
        if (uri == null) return
        when (action) {
            FileAction.EXPORT, FileAction.EXPORT_WITH_VIDEOS -> transfer(
                "未能保存完整备份，请检查存储空间、目标位置和私房菜视频后重试。",
            ) {
                withContext(Dispatchers.IO) {
                    var saved = false
                    try {
                        requireNotNull(resolver.openOutputStream(uri, "wt")).use { output ->
                            when (action) {
                                FileAction.EXPORT -> output.write(store.export())
                                else -> store.writeArchive(output)
                            }
                        }
                        saved = true
                    } finally {
                        if (!saved) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                    }
                }
                if (action == FileAction.EXPORT_WITH_VIDEOS) "已保存含视频的本机备份。" else "已保存本机备份。"
            }
            FileAction.IMPORT -> transfer("无法读取这份云食册本机备份，当前数据未改变。") {
                selected = null
                restored = emptySet()
                val result = withContext(Dispatchers.IO) {
                    requireNotNull(resolver.openInputStream(uri)).use(store::readPreview)
                }
                selected = SelectedBackup(uri, result)
                if (result.videos.isEmpty()) "备份已检查，可以分项恢复。"
                else "可以分项恢复。视频将在恢复私房菜时检查。"
            }
        }
    }

    fun requestRestore(section: DeviceBackupSection) {
        if (!busy && preview != null && section !in restored) confirming = section
    }

    fun cancelRestore() { confirming = null }

    fun confirmRestore(resolver: ContentResolver) {
        if (busy) return
        val section = confirming ?: return
        val (uri, preview) = selected ?: return
        confirming = null
        transfer("无法恢复${section.title}，请检查文件、数据容量和可用空间。") {
            if (section == DeviceBackupSection.PRIVATE_RECIPES && preview.videos.isNotEmpty()) {
                store.restorePrivateRecipesWithVideos(preview) { requireNotNull(resolver.openInputStream(uri)) }
            } else {
                store.restore(preview.document, section)
            }
            restored = restored + section
            "已恢复${section.title}。"
        }
    }

    private fun transfer(failure: String, operation: suspend () -> String) {
        if (busy) return
        working = true
        status = null
        hasError = false
        viewModelScope.launch {
            try {
                status = operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showFailure(failure)
            } finally {
                working = false
            }
        }
    }

    private fun showFailure(message: String) {
        status = message
        hasError = true
    }
}
