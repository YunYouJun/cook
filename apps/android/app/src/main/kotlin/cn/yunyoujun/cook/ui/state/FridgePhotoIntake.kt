package cn.yunyoujun.cook.ui.state

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.data.local.FridgePhotoFocus
import cn.yunyoujun.cook.data.local.FridgeProduceModelStore
import cn.yunyoujun.cook.data.local.FridgeProduceMatch
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Keeps the photo and local processing across rotation and visits to account or membership. */
internal class FridgePhotoIntake(private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    var draft by mutableStateOf<Draft?>(
        if (savedState.get<String>(GALLERY_PENDING) == ACTIVE) Draft().apply { choosingGalleryPhoto = true } else null,
    )
        private set
    private var recognitionJob: Job? = null
    private var modelJob: Job? = null

    fun open() {
        close()
        draft = Draft()
    }

    fun close() {
        if (savedState.get<String>(GALLERY_PENDING) == ACTIVE) savedState[GALLERY_PENDING] = DISCARDED
        draft?.batchReview?.close()
        draft = null
        recognitionJob?.cancel()
        modelJob?.cancel()
        recognitionJob = null
        modelJob = null
    }

    fun chooseGalleryPhoto(current: Draft, launch: () -> Unit) {
        if (draft !== current || current.working || current.saving || current.waitingForPhoto) return
        if (savedState.get<String>(GALLERY_PENDING) != null) {
            current.photoError = "上次打开的照片选择器还未关闭。"
            return
        }
        savedState[GALLERY_PENDING] = ACTIVE
        current.choosingGalleryPhoto = true
        try {
            launch()
        } catch (_: ActivityNotFoundException) {
            acceptGalleryPhoto(null)
            current.photoError = "无法打开系统照片选择器，可以拍照或手动录入。"
        } catch (_: SecurityException) {
            acceptGalleryPhoto(null)
            current.photoError = "无法打开系统照片选择器，可以拍照或手动录入。"
        }
    }

    fun acceptGalleryPhoto(uri: Uri?) {
        if (savedState.remove<String>(GALLERY_PENDING) != ACTIVE) return
        val current = draft ?: return
        current.choosingGalleryPhoto = false
        current.gallerySelection = uri
    }

    fun startBatchReview(
        current: Draft,
        jpeg: ByteArray,
        service: FridgeVisionService,
        sessions: YunlefunSessionManager,
        normalize: (String) -> String,
    ) {
        if (draft !== current || current.batchReview != null) return
        current.batchReview = FridgeBatchReview(jpeg, service, sessions, normalize, viewModelScope)
    }

    fun endBatchReview(current: Draft, replacePhoto: Boolean) {
        if (draft !== current) return
        current.batchReview?.close()
        current.batchReview = null
        if (replacePhoto) current.clearPhoto() else current.reviewing = true
    }

    fun checkProduceModel(current: Draft, store: FridgeProduceModelStore) {
        if (draft !== current || current.modelStatus != ModelStatus.Checking || modelJob?.isActive == true) return
        modelJob = viewModelScope.launch {
            store.prepareOfflineModel()?.let {
                current.modelStatus = ModelStatus.Ready(it)
                return@launch
            }
            current.modelStatus = when (val status = store.status()) {
                is FridgeProduceModelStore.Status.Installed -> ModelStatus.Ready(status.file)
                FridgeProduceModelStore.Status.Resumable -> ModelStatus.Paused
                FridgeProduceModelStore.Status.Missing -> ModelStatus.Missing
            }
        }
    }

    fun downloadProduceModel(current: Draft, store: FridgeProduceModelStore) {
        if (draft !== current || modelJob?.isActive == true || current.modelStatus is ModelStatus.Ready) return
        val received = MutableStateFlow(0L)
        current.modelStatus = ModelStatus.Downloading(received)
        modelJob = viewModelScope.launch {
            current.modelStatus = try {
                ModelStatus.Ready(store.download { received.value = it })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                ensureActive()
                ModelStatus.Failed
            }
        }
    }

    fun pauseProduceModel(current: Draft) {
        if (draft !== current || current.modelStatus !is ModelStatus.Downloading) return
        modelJob?.cancel()
        current.modelStatus = ModelStatus.Paused
    }

    suspend fun recheckModelAfterInferenceFailure(current: Draft, store: FridgeProduceModelStore) {
        val installed = store.installedModel()
        val repaired = if (installed == null) store.prepareOfflineModel() else null
        coroutineContext.ensureActive()
        if (draft !== current) return
        current.modelStatus = when {
            repaired != null -> ModelStatus.Ready(repaired)
            installed != null -> ModelStatus.RuntimeUnavailable(installed)
            else -> ModelStatus.NeedsRepair
        }
        current.waitingForModel = installed == null
    }

    fun retryProduceRecognition(current: Draft) {
        val model = current.modelStatus as? ModelStatus.RuntimeUnavailable ?: return
        if (draft !== current || current.working || current.photo == null) return
        current.modelStatus = ModelStatus.Ready(model.file)
        current.waitingForModel = true
    }

    fun processPhoto(current: Draft, readingPhoto: Boolean = false, work: suspend () -> Unit) {
        if (draft !== current || current.working || current.saving || current.waitingForPhoto) return
        current.working = true
        current.readingPhoto = readingPhoto
        recognitionJob = viewModelScope.launch {
            try {
                work()
            } finally {
                current.readingPhoto = false
                current.working = false
            }
        }
    }

    fun saveSingleItem(current: Draft, writeItem: () -> Unit) {
        if (draft !== current || current.working || current.saving || current.waitingForPhoto) return
        current.photoError = null
        current.saving = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { writeItem() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                current.photoError = "食材未保存，请重试。"
                return@launch
            } finally {
                current.saving = false
            }
            if (draft === current) close()
        }
    }

    sealed interface ModelStatus {
        data object Checking : ModelStatus
        data object Missing : ModelStatus
        data class Downloading(val receivedBytes: StateFlow<Long>) : ModelStatus
        data object Paused : ModelStatus
        data object Failed : ModelStatus
        data object NeedsRepair : ModelStatus
        data class RuntimeUnavailable(val file: File) : ModelStatus
        data class Ready(val file: File) : ModelStatus
    }

    class Photo(val bytes: ByteArray, val captureRotation: Int? = null)

    class Draft {
        var reviewing by mutableStateOf(false)
        var recognizedLabelText by mutableStateOf("")
            private set
        private var editedLabelText by mutableStateOf<String?>(null)
        var labelText: String
            get() = editedLabelText ?: recognizedLabelText
            set(value) {
                editedLabelText = value.takeUnless { it == recognizedLabelText }
            }
        var showingRecognizedLabel by mutableStateOf(false)
        var produceMatch by mutableStateOf(FridgeProduceMatch())
        var ingredientNameOverride by mutableStateOf<String?>(null)
        var dates by mutableStateOf(FridgePhotoDates())
        var selectedCategoryId by mutableStateOf<String?>(null)
        var photoInfo by mutableStateOf<String?>(null)
        var photoError by mutableStateOf<String?>(null)
        var working by mutableStateOf(false)
        var readingPhoto by mutableStateOf(false)
        var saving by mutableStateOf(false)
        var choosingGalleryPhoto by mutableStateOf(false)
        var gallerySelection by mutableStateOf<Uri?>(null)
        val waitingForPhoto: Boolean get() = choosingGalleryPhoto || gallerySelection != null || readingPhoto
        var modelStatus by mutableStateOf<ModelStatus>(ModelStatus.Checking)
        var photo by mutableStateOf<Photo?>(null)
        var capturedPhoto by mutableStateOf<Photo?>(null)
        var recognitionFocus by mutableStateOf<FridgePhotoFocus?>(null)
        var waitingForModel by mutableStateOf(false)
        var batchReview by mutableStateOf<FridgeBatchReview?>(null)

        val hasReviewEdits: Boolean
            get() = editedLabelText != null || ingredientNameOverride != null ||
                dates != FridgePhotoDates() || selectedCategoryId != null

        fun setRecognizedLabel(text: String, keepEdits: Boolean = false) {
            recognizedLabelText = text
            if (!keepEdits) {
                editedLabelText = null
                showingRecognizedLabel = false
            }
        }

        fun resetReviewEdits() {
            editedLabelText = null
            ingredientNameOverride = null
            dates = FridgePhotoDates()
            selectedCategoryId = null
        }

        fun clearPhoto() {
            photoInfo = null
            photoError = null
            setRecognizedLabel("")
            produceMatch = FridgeProduceMatch()
            resetReviewEdits()
            reviewing = false
            photo = null
            capturedPhoto = null
            recognitionFocus = null
            waitingForModel = false
        }
    }

    private companion object {
        const val GALLERY_PENDING = "fridgeGalleryPending"
        const val ACTIVE = "active"
        const val DISCARDED = "discarded"
    }
}
