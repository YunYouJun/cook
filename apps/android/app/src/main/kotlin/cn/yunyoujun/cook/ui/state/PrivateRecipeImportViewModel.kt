package cn.yunyoujun.cook.ui.state

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.core.sync.PrivateRecipeFile
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.PrivateRecipesStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class PrivateRecipeImportViewModel(private val store: PrivateRecipesStore) : ViewModel() {
    private var preview by mutableStateOf<PrivateRecipesStore.ImportPreview?>(null)
    val plan get() = preview?.plan
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun readFile(resolver: ContentResolver, uri: Uri) = prepare {
        resolver.openInputStream(uri)?.use { BoundedIoStreams.readUtf8Bounded(it, PrivateRecipeFile.BYTE_LIMIT) }
            ?: error("无法读取选中的文件。")
    }

    fun readClipboard(text: CharSequence?) = prepare {
        text?.takeUnless { it.isBlank() }?.toString() ?: error("剪贴板里没有可导入的 JSON 文本。")
    }

    private fun prepare(readText: () -> String) {
        if (preview != null) return
        performImport {
            preview = withContext(Dispatchers.IO) { store.previewImport(readText()) }
        }
    }

    fun confirm(keepCopies: Boolean) {
        val pending = preview ?: return
        performImport {
            try {
                val count = withContext(Dispatchers.IO) { store.commitImport(pending, keepCopies) }
                preview = null
                message = "已导入 $count 道私房菜。"
            } catch (_: PrivateRecipesStore.ImportChanged) {
                preview = withContext(Dispatchers.IO) { store.previewImport(pending.incoming) }
                message = "本机私房菜已变化，请核对更新后的预览再确认。"
            }
        }
    }

    fun cancel() {
        if (!working) {
            preview = null
            message = null
        }
    }

    fun clearMessage() { message = null }

    private fun performImport(action: suspend () -> Unit) {
        if (working) return
        working = true
        message = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "导入失败，请检查文件内容后重试。"
            } finally {
                working = false
            }
        }
    }
}
