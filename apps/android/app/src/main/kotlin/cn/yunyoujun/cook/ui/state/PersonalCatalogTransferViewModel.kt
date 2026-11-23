package cn.yunyoujun.cook.ui.state

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.core.sync.PersonalCatalog
import cn.yunyoujun.cook.core.sync.PersonalCatalogException
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.PersonalCatalogState
import cn.yunyoujun.cook.data.local.PersonalCatalogStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException

internal class PersonalCatalogTransferViewModel(private val store: PersonalCatalogStore) : ViewModel() {
    var pending by mutableStateOf<PersonalCatalog?>(null)
        private set
    var working by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun read(resolver: ContentResolver, uri: Uri) = transfer("无法导入词典。") {
        pending = withContext(Dispatchers.IO) {
            val bytes = resolver.openInputStream(uri)?.use {
                BoundedIoStreams.readBytesBounded(it, PersonalCatalog.FILE_LIMIT)
            } ?: error("无法读取选中的文件。")
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                throw PersonalCatalogException.Invalid()
            }
            PersonalCatalog.parse(text)
        }
    }

    fun export(resolver: ContentResolver, uri: Uri) = transfer("无法导出词典。") {
        withContext(Dispatchers.IO) {
            val bytes = store.exportJson().toByteArray(Charsets.UTF_8)
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入选中的文件。")
        }
        message = "已导出个人食材与厨具词典。"
    }

    fun confirm(expectedState: PersonalCatalogState) {
        val document = pending ?: return
        transfer("合并词典失败，原有条目已保留。") {
            withContext(Dispatchers.IO) { store.restoreBackup(document, expectedState) }
            pending = null
            message = "已合并个人食材与厨具词典。"
        }
    }

    fun cancel() {
        if (working) return
        pending = null
        message = null
    }

    fun clearMessage() { message = null }

    private fun transfer(failure: String, action: suspend () -> Unit) {
        if (working) return
        working = true
        message = null
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: PersonalCatalogStore.ImportChanged) {
                message = "本机词典已变化，请核对更新后的数量再确认。"
            } catch (error: Exception) {
                message = error.message ?: failure
            } finally {
                working = false
            }
        }
    }
}
