package cn.yunyoujun.cook.ui.screens

import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import cn.yunyoujun.cook.data.local.BoundedIoStreams
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun FridgeCameraDialog(
    capturedPhoto: FridgePhotoIntake.Photo?,
    onCapture: (FridgePhotoIntake.Photo) -> Unit,
    onRetake: () -> Unit,
    onUsePhoto: (FridgePhotoIntake.Photo) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var attempt by remember { mutableIntStateOf(0) }
    val controller = remember(context, attempt) {
        LifecycleCameraController(context.applicationContext).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
        }
    }
    val finder = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            contentDescription = "冰箱食材相机取景画面"
        }
    }
    val deliver by rememberUpdatedState(onCapture)
    var activeSession by remember { mutableStateOf<Any?>(null) }
    var streaming by remember { mutableStateOf(false) }
    var previewTimedOut by remember { mutableStateOf(false) }
    var bothCameras by remember { mutableStateOf(false) }
    var useFrontCamera by rememberSaveable { mutableStateOf(false) }
    var useFlash by rememberSaveable { mutableStateOf(false) }
    var takingPhoto by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    if (capturedPhoto == null && !previewTimedOut) {
        DisposableEffect(controller, finder, owner, mainExecutor) {
            val session = Any()
            activeSession = session
            streaming = false
            error = null
            var bound = false
            val stream = Observer<PreviewView.StreamState> { state ->
                streaming = state == PreviewView.StreamState.STREAMING
            }
            finder.previewStreamState.observe(owner, stream)
            val initialization = controller.initializationFuture
            initialization.addListener(listener@{
                if (activeSession !== session) return@listener
                runCatching {
                    initialization.get()
                    val rear = controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    val front = controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                    check(front || rear)
                    bothCameras = front && rear
                    useFrontCamera = front && (!rear || useFrontCamera)
                    controller.cameraSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                    controller.bindToLifecycle(owner)
                    bound = true
                    finder.controller = controller
                }.onFailure {
                    error = "无法开启相机。可以返回选择相册图片或手动入库。"
                }
            }, mainExecutor)
            onDispose {
                activeSession = null
                takingPhoto = false
                finder.previewStreamState.removeObserver(stream)
                finder.controller = null
                if (bound) controller.unbind()
            }
        }
        if (!streaming && error == null) {
            LaunchedEffect(controller) {
                // A system camera privacy block can leave the attached view idle indefinitely.
                delay(12.seconds)
                if (!streaming) previewTimedOut = true
            }
        }
    }

    val unavailable = !streaming && (previewTimedOut || error != null)
    val message = error ?: if (unavailable) {
        "相机未能开始取景。请检查系统相机访问设置后重试，也可以返回选图。"
    } else null
    val canCapture = streaming && !takingPhoto

    fun capture() {
        val session = activeSession ?: return
        if (!canCapture) return
        takingPhoto = true
        error = null
        fun complete(result: Result<FridgePhotoIntake.Photo>) {
            mainExecutor.execute {
                if (activeSession !== session) return@execute
                takingPhoto = false
                result.onSuccess(deliver).onFailure {
                    error = if (it is BoundedIoStreams.TooLargeException) {
                        "照片超过 30 MB，请从相册选择较小的图片。"
                    } else {
                        "拍照失败，请重试。"
                    }
                }
            }
        }
        runCatching {
            val canFlash = controller.cameraInfo?.hasFlashUnit() == true
            controller.imageCaptureFlashMode = if (canFlash && useFlash) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
            controller.takePicture(Dispatchers.Default.asExecutor(), object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val result = runCatching {
                        try {
                            check(image.format == ImageFormat.JPEG)
                            val buffer = image.planes.single().buffer
                            val size = buffer.remaining()
                            if (size > FridgePhotoInspector.MAX_PHOTO_BYTES) {
                                throw BoundedIoStreams.TooLargeException(FridgePhotoInspector.MAX_PHOTO_BYTES)
                            }
                            check(size > 0)
                            val bytes = ByteArray(size)
                            buffer.get(bytes)
                            FridgePhotoIntake.Photo(bytes, image.imageInfo.rotationDegrees)
                        } finally {
                            image.close()
                        }
                    }
                    complete(result)
                }

                override fun onError(exception: ImageCaptureException) = complete(Result.failure(exception))
            })
        }.onFailure { complete(Result.failure(it)) }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (capturedPhoto != null) {
                FridgeCameraReview(capturedPhoto, onDismiss, onRetake, onUsePhoto)
                return@Surface
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("拍摄冰箱食材", style = MaterialTheme.typography.titleLarge)
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AndroidView(factory = { finder }, modifier = Modifier.fillMaxSize())
                    if (!streaming && message == null) Text("正在开启相机…")
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    if (bothCameras) {
                        TextButton(enabled = canCapture, onClick = {
                            val next = !useFrontCamera
                            runCatching {
                                controller.cameraSelector = if (next) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                                useFrontCamera = next
                                useFlash = false
                                error = null
                            }.onFailure { error = "无法切换相机，请继续使用当前镜头。" }
                        }) { Text("切换镜头") }
                    }
                    if (streaming && controller.cameraInfo?.hasFlashUnit() == true) {
                        TextButton(enabled = canCapture, onClick = { useFlash = !useFlash }) {
                            Text(if (useFlash) "关闭闪光灯" else "开启闪光灯")
                        }
                    }
                    if (!takingPhoto && unavailable) {
                        Button(onClick = {
                            previewTimedOut = false
                            attempt++
                        }) { Text("重试相机") }
                    } else {
                        Button(enabled = canCapture, onClick = ::capture) {
                            Text(if (takingPhoto) "正在拍摄…" else "拍摄")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FridgeCameraReview(
    photo: FridgePhotoIntake.Photo,
    onDismiss: () -> Unit,
    onRetake: () -> Unit,
    onUsePhoto: (FridgePhotoIntake.Photo) -> Unit,
) {
    var preview by remember(photo) { mutableStateOf<ImageBitmap?>(null) }
    var previewFailed by remember(photo) { mutableStateOf(false) }
    LaunchedEffect(photo) {
        try {
            preview = FridgePhotoInspector.preview(photo.bytes, photo.captureRotation).asImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            previewFailed = true
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("查看拍摄照片", style = MaterialTheme.typography.titleLarge)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val image = preview
            when {
                image != null -> Image(
                    bitmap = image,
                    contentDescription = "刚拍摄的照片",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                previewFailed -> Text("无法预览这张照片，请重拍。", color = MaterialTheme.colorScheme.error)
                else -> Text("正在准备照片…")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = onRetake) { Text("重拍") }
            Button(enabled = preview != null, onClick = { onUsePhoto(photo) }) { Text("使用照片") }
        }
    }
}
