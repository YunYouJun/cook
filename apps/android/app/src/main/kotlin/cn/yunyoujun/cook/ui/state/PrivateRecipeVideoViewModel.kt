package cn.yunyoujun.cook.ui.state

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.core.sync.PrivateRecipe
import cn.yunyoujun.cook.core.sync.PrivateRecipeVideo
import cn.yunyoujun.cook.data.local.PrivateRecipeVideoStore
import cn.yunyoujun.cook.data.local.PrivateRecipesStore
import cn.yunyoujun.cook.data.local.PrivateVideoDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Owns attachment drafts and file operations for one private recipe destination. */
internal class PrivateRecipeVideoViewModel(
    private val recipes: PrivateRecipesStore,
    private val videos: PrivateRecipeVideoStore,
) : ViewModel() {
    var draft by mutableStateOf<PrivateVideoDraft?>(null)
        private set
    var keepOriginal by mutableStateOf(true)
        private set
    var saving by mutableStateOf(false)
        private set
    var importing by mutableStateOf(false)
        private set
    var exporting by mutableStateOf(false)
        private set
    var saved by mutableStateOf(false)
        private set
    var editorError by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
        private set

    private var awaitingPicker by mutableStateOf(false)
    val editorBusy: Boolean get() = awaitingPicker || importing || saving || saved

    fun chooseVideo(launchPicker: () -> Unit) {
        if (editorBusy) return
        awaitingPicker = true
        editorError = null
        try {
            launchPicker()
        } catch (_: Exception) {
            awaitingPicker = false
            editorError = "无法打开系统文件选择器。"
        }
    }

    fun videoChosen(uri: Uri?) {
        val wasAwaitingPicker = awaitingPicker
        awaitingPicker = false
        if (uri == null) return
        if (!wasAwaitingPicker) {
            editorError = "视频选择已失效，请重新选择。"
            return
        }
        importing = true
        viewModelScope.launch {
            try {
                val selected = videos.stageFromUri(uri)
                discardDraft()
                draft = selected
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                editorError = error.message ?: "请选择可播放的 MP4、MOV 或 M4V 视频。"
            } finally {
                importing = false
            }
        }
    }

    fun removeVideo() {
        if (editorBusy) return
        discardDraft()
        keepOriginal = false
        editorError = null
    }

    fun finishEditor() {
        discardDraft()
        keepOriginal = true
        editorError = null
        saved = false
    }

    private fun discardDraft() {
        val editing = draft ?: return
        draft = null
        videos.discard(editing)
    }

    fun save(recipe: PrivateRecipe) {
        if (editorBusy) return
        val selected = draft
        saving = true
        editorError = null
        viewModelScope.launch {
            try {
                val removedOldVideo = commit(recipe, selected)
                if (!removedOldVideo) message = "菜谱已保存，旧视频副本未能清理。"
                saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                editorError = error.message ?: "私房菜未能保存，请重试。"
            } finally {
                saving = false
            }
        }
    }

    private suspend fun commit(recipe: PrivateRecipe, selected: PrivateVideoDraft?): Boolean = withContext(Dispatchers.IO) {
        var installed: PrivateRecipeVideo? = null
        try {
            installed = selected?.let { videos.installWithoutDiscarding(it) }
            ensureActive()
            val finalRecipe = if (installed == null) recipe else recipe.copy(video = installed)
            val oldVideo = recipes.upsertRecipe(finalRecipe)?.video
            oldVideo == null || oldVideo == finalRecipe.video ||
                runCatching { recipes.removeUnreferencedVideo(oldVideo, videos) }.getOrDefault(false)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                installed?.let { runCatching { recipes.removeUnreferencedVideo(it, videos) } }
            }
            throw error
        }
    }

    fun export(id: String?, uri: Uri) {
        if (exporting) return
        exporting = true
        message = "正在保存视频…"
        viewModelScope.launch {
            try {
                val video = recipes.state.value.recipes.firstNotNullOfOrNull { recipe ->
                    recipe.video?.takeIf { it.id == id }
                }
                videos.exportToDocument(video, uri)
                message = "已保存视频文件。换机后可在私房菜编辑中选用它。"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "无法保存视频，请检查附件、存储空间和目标位置。"
            } finally {
                exporting = false
            }
        }
    }

    fun clearMessage() { message = null }

    override fun onCleared() {
        discardDraft()
    }
}
