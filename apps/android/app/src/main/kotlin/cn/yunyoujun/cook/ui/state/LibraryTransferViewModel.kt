package cn.yunyoujun.cook.ui.state

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.sync.UserDataDocument
import cn.yunyoujun.cook.core.sync.UserDataExportFile
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.remote.UserDataSyncException
import cn.yunyoujun.cook.data.remote.UserLibraryScope
import cn.yunyoujun.cook.platform.libraryShareIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

internal class LibraryTransferViewModel : ViewModel() {
    enum class DocumentAction { SAVE, IMPORT }

    private data class PendingDocument(val action: DocumentAction, val scope: UserLibraryScope)
    private sealed interface Output {
        val scope: UserLibraryScope
        data class Share(override val scope: UserLibraryScope, val intent: Intent) : Output
        data class Clipboard(override val scope: UserLibraryScope, val json: String) : Output
    }

    private var pendingDocument by mutableStateOf<PendingDocument?>(null)
    private var output by mutableStateOf<Output?>(null)
    var isWorking by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    val busy: Boolean get() = pendingDocument != null || isWorking || output != null
    val outputReady: Boolean get() = output != null

    fun chooseDocument(action: DocumentAction, scope: UserLibraryScope, launch: () -> Unit) {
        if (busy) return
        message = null
        pendingDocument = PendingDocument(action, scope)
        try {
            launch()
        } catch (_: Exception) {
            pendingDocument = null
            message = "无法打开系统文件选择器。"
        }
    }

    fun documentReturned(context: Context, container: AppContainer, action: DocumentAction, uri: Uri?) {
        val pending = pendingDocument?.takeIf { it.action == action }
        if (pending != null) pendingDocument = null
        if (uri == null) return
        if (pending == null) {
            message = "文件选择已失效，请重新发起。"
            return
        }
        val resolver = context.applicationContext.contentResolver
        val scope = pending.scope
        transfer(if (action == DocumentAction.SAVE) "导出失败" else "导入未生效") {
            checkScope(container, scope)
            when (action) {
                DocumentAction.SAVE -> {
                    val json = container.userLibraryRepository.exportUserDataDocumentJson(scope)
                    withContext(Dispatchers.IO) {
                        checkScope(container, scope)
                        val stream = resolver.openOutputStream(uri) ?: throw IOException("无法打开写入目标文件。")
                        stream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                    message = "已保存${scope.label}的收藏与浏览历史。"
                }
                DocumentAction.IMPORT -> {
                    val json = withContext(Dispatchers.IO) {
                        val stream = resolver.openInputStream(uri) ?: throw IOException("无法读取选中的文件。")
                        stream.use { BoundedIoStreams.readUtf8Bounded(it, UserDataExportFile.MAX_FILE_BYTES) }
                    }
                    importJson(container, scope, json)
                }
            }
        }
    }

    fun importClipboard(container: AppContainer, scope: UserLibraryScope, text: CharSequence?) {
        if (busy) return
        if (text.isNullOrBlank()) {
            message = "剪贴板里没有可导入的 JSON 文本。"
            return
        }
        transfer("导入未生效") {
            checkScope(container, scope)
            importJson(container, scope, text.toString())
        }
    }

    fun share(context: Context, container: AppContainer, scope: UserLibraryScope) = transfer("无法分享") {
        val json = container.userLibraryRepository.exportUserDataDocumentJson(scope)
        output = Output.Share(scope, libraryShareIntent(context.applicationContext, json))
    }

    fun copy(container: AppContainer, scope: UserLibraryScope) = transfer("无法复制") {
        output = Output.Clipboard(scope, container.userLibraryRepository.exportUserDataDocumentJson(scope))
    }

    fun deliverOutput(context: Context, container: AppContainer) {
        val ready = output ?: return
        output = null
        try {
            checkScope(container, ready.scope)
            when (ready) {
                is Output.Share -> context.startActivity(Intent.createChooser(ready.intent, "分享收藏与浏览历史"))
                is Output.Clipboard -> {
                    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: error("无法访问剪贴板。")
                    val clip = ClipData.newPlainText("云食册收藏与浏览历史", ready.json)
                    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    clipboard.setPrimaryClip(clip)
                    message = "已复制${ready.scope.label}的 JSON。"
                }
            }
        } catch (error: Exception) {
            message = "导出失败：${error.transferMessage()}"
        }
    }

    fun clearMessage() { message = null }

    private suspend fun importJson(container: AppContainer, scope: UserLibraryScope, json: String) {
        val (favorites, visits) = container.userLibraryRepository.importUserDataDocumentJson(json, scope).getOrThrow()
        message = "已合并导入，${scope.label}共 $favorites 条收藏、$visits 条浏览历史。"
    }

    private fun transfer(failure: String, action: suspend () -> Unit) {
        if (busy) return
        isWorking = true
        message = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "$failure：${error.transferMessage()}"
            } finally {
                isWorking = false
            }
        }
    }

    private fun checkScope(container: AppContainer, scope: UserLibraryScope) {
        val state = container.yunlefunSessionManager.state.value
        if (!state.isHydrated) throw UserDataSyncException.LibraryUnreadable()
        if (state.libraryScope != scope) throw UserDataSyncException.LibraryChanged()
    }

    private fun Exception.transferMessage() = when (this) {
        is UserDataSyncException -> message ?: "请重新发起。"
        is UserDataDocument.InvalidDocumentException -> "文件或文本未通过互通 JSON 校验。"
        else -> "请检查文件、储存空间或剪贴板后重试。"
    }

    private val UserLibraryScope.label: String get() = if (usesAccountLibrary) "当前账号库" else "本机库"
}
