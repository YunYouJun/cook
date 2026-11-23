package cn.yunyoujun.cook.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.local.FridgePhotoFocus
import cn.yunyoujun.cook.data.local.FridgePhotoInspector
import cn.yunyoujun.cook.ui.state.FridgePhotoIntake
import kotlinx.coroutines.CancellationException

@Composable
internal fun FridgePhotoFocusDialog(
    photo: FridgePhotoIntake.Photo,
    initialFocus: FridgePhotoFocus?,
    onRecognize: (FridgePhotoFocus?) -> Unit,
    onDismiss: () -> Unit,
) {
    var preview by remember(photo) { mutableStateOf<Bitmap?>(null) }
    var previewFailed by remember(photo) { mutableStateOf(false) }
    LaunchedEffect(photo) {
        try {
            preview = FridgePhotoInspector.preview(photo.bytes, photo.captureRotation)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            previewFailed = true
        }
    }
    val original = initialFocus ?: FridgePhotoFocus()
    var sizeFraction by rememberSaveable { mutableFloatStateOf(original.sizeFraction) }
    var horizontal by rememberSaveable { mutableFloatStateOf(original.horizontalPosition) }
    var vertical by rememberSaveable { mutableFloatStateOf(original.verticalPosition) }
    val focus = FridgePhotoFocus(sizeFraction, horizontal, vertical)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择识别范围") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val bitmap = preview
                when {
                    previewFailed -> Text("无法预览照片，可以返回换图。", color = MaterialTheme.colorScheme.error)
                    bitmap == null -> Text("正在准备照片…")
                    else -> {
                        Text("点照片移动识别框，把一份食材放在框内。", style = MaterialTheme.typography.bodySmall)
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxWidth().height(168.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val aspect = bitmap.width.toFloat() / bitmap.height
                            val imageWidth = minOf(maxWidth, maxHeight * aspect)
                            Box(
                                Modifier.size(imageWidth, imageWidth / aspect)
                                    .pointerInput(sizeFraction) {
                                        detectTapGestures { point ->
                                            val width = size.width.toFloat()
                                            val height = size.height.toFloat()
                                            val side = minOf(width, height) * sizeFraction
                                            horizontal = positionForCenter(point.x, width, side)
                                            vertical = positionForCenter(point.y, height, side)
                                        }
                                    },
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "所选照片",
                                    modifier = Modifier.matchParentSize(),
                                    contentScale = ContentScale.FillBounds,
                                )
                                Canvas(Modifier.matchParentSize()) {
                                    val bounds = focus.bounds(size.width, size.height)
                                    val corner = Offset(bounds.left, bounds.top)
                                    val frame = Size(bounds.width(), bounds.height())
                                    drawRect(Color.Black, corner, frame, style = Stroke(4.dp.toPx()))
                                    drawRect(Color.White, corner, frame, style = Stroke(2.dp.toPx()))
                                }
                            }
                        }
                        FridgeFocusSlider("范围大小", sizeFraction, { sizeFraction = it }, .10f..1f)
                        FridgeFocusSlider("左右位置", horizontal, { horizontal = it }, enabled = sizeFraction < 1f || bitmap.width > bitmap.height)
                        FridgeFocusSlider("上下位置", vertical, { vertical = it }, enabled = sizeFraction < 1f || bitmap.height > bitmap.width)
                    }
                }
                if (initialFocus != null) {
                    TextButton(onClick = { onRecognize(null) }) { Text("识别整张照片") }
                }
            }
        },
        confirmButton = {
            Button(enabled = preview != null, onClick = { onRecognize(focus) }) { Text("识别框内食材") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun FridgeFocusSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
) {
    Text(label, style = MaterialTheme.typography.bodySmall)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    )
}

private fun positionForCenter(point: Float, length: Float, side: Float): Float {
    val travel = length - side
    if (travel <= 0f) return .5f
    return ((point - side / 2) / travel).coerceIn(0f, 1f)
}
