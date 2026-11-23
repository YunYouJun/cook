package cn.yunyoujun.cook.ui.screens

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.core.recipe.RecipeTextBlock
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.data.remote.LoadedRecipeImage
import cn.yunyoujun.cook.data.remote.RecipeImageLoader
import cn.yunyoujun.cook.data.remote.isRecipeImageUrl
import cn.yunyoujun.cook.platform.recipeImageShareIntent
import cn.yunyoujun.cook.platform.saveRecipeImageToGallery
import cn.yunyoujun.cook.platform.writeRecipeImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

internal data class RecipePhoto(val blockIndex: Int, val url: String, val label: String, val aspectRatio: Float)

internal fun recipePhotos(text: RecipeText?, library: RecipeTextLibrary?, name: String): List<RecipePhoto> =
    text?.blocks.orEmpty().mapIndexedNotNull { index, block ->
        if (block !is RecipeTextBlock.ImageBlock) return@mapIndexedNotNull null
        val url = library?.imageURL(block.src) ?: block.src
        if (!isRecipeImageUrl(url)) return@mapIndexedNotNull null
        val label = block.alt?.trim()?.takeIf { it.isNotEmpty() } ?: "${name}的配图"
        val ratio = (block.width.toFloat() / block.height.coerceAtLeast(1)).coerceIn(0.55f, 2.2f)
        RecipePhoto(index, url, label, ratio)
    }

@Composable
internal fun RecipePhotoPreview(photo: RecipePhoto, loader: RecipeImageLoader, onOpen: () -> Unit) {
    val result = recipeImage(photo, loader, attempt = 0)
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(photo.aspectRatio)
                .clickable(role = Role.Button, onClickLabel = "查看配图", onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            val image = result?.getOrNull()
            when {
                image != null -> Image(image.bitmap.asImageBitmap(), photo.label, Modifier.fillMaxSize())
                result == null -> CircularProgressIndicator()
                else -> Text("配图加载失败 · 点此重试", Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun recipeImage(photo: RecipePhoto, loader: RecipeImageLoader, attempt: Int): Result<LoadedRecipeImage>? =
    key(photo.url, loader, attempt) {
        val result by produceState(loader.cached(photo.url)?.let { Result.success(it) }) {
            if (value == null) {
                value = try {
                    Result.success(loader.load(photo.url))
                } catch (error: IOException) {
                    Result.failure(error)
                }
            }
        }
        result
    }

@Composable
internal fun RecipeImageViewer(
    recipeId: Int,
    photos: List<RecipePhoto>,
    initialBlock: Int,
    loader: RecipeImageLoader,
    onClose: () -> Unit,
) {
    val start = photos.indexOfFirst { it.blockIndex == initialBlock }
    if (start < 0) return
    val pager = rememberPagerState(initialPage = start) { photos.size }
    val photo = photos[pager.currentPage]
    var attempt by remember(photo.url) { mutableIntStateOf(0) }
    val result = recipeImage(photo, loader, attempt)
    val current = result?.getOrNull()
    var scale by remember(photo.blockIndex) { mutableFloatStateOf(1f) }
    var offset by remember(photo.blockIndex) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var exportIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    fun runFileAction(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "无法处理配图，请重试。", Toast.LENGTH_SHORT).show()
            } finally {
                busy = false
            }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { response ->
        val selected = exportIndex?.let(photos::getOrNull)
        exportIndex = null
        val uri = response.data?.data
        if (response.resultCode == Activity.RESULT_OK && selected != null && uri != null) {
            runFileAction {
                writeRecipeImage(context, uri, loader.load(selected.url))
                Toast.makeText(context, "图片已保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun transform(newScale: Float, newOffset: Offset) {
        val image = current?.bitmap ?: return
        scale = newScale.coerceIn(1f, 5f)
        val fit = minOf(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height)
        val maxX = ((image.width * fit * scale - viewport.width) / 2).coerceAtLeast(0f)
        val maxY = ((image.height * fit * scale - viewport.height) / 2).coerceAtLeast(0f)
        offset = Offset(newOffset.x.coerceIn(-maxX, maxX), newOffset.y.coerceIn(-maxY, maxY))
    }

    LaunchedEffect(viewport) { transform(scale, offset) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        MaterialTheme(colorScheme = darkColorScheme(), typography = MaterialTheme.typography) {
            Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = onClose) { Text("关闭") }
                        Text("${pager.currentPage + 1} / ${photos.size}", Modifier.align(Alignment.CenterVertically))
                        Row {
                            TextButton(enabled = current != null && !busy, onClick = {
                                current?.let { image ->
                                    val name = "cook-$recipeId-${pager.currentPage + 1}.${image.extension}"
                                    runFileAction { context.startActivity(Intent.createChooser(recipeImageShareIntent(context, name, image), "分享配图")) }
                                }
                            }) { Text("分享") }
                            TextButton(enabled = current != null && !busy, onClick = {
                                current?.let { image ->
                                    val name = "cook-$recipeId-${pager.currentPage + 1}.${image.extension}"
                                    runFileAction {
                                        if (Build.VERSION.SDK_INT >= 29) {
                                            saveRecipeImageToGallery(context, name, image)
                                            Toast.makeText(context, "图片已保存到相册", Toast.LENGTH_SHORT).show()
                                        } else {
                                            exportIndex = pager.currentPage
                                            export.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                                addCategory(Intent.CATEGORY_OPENABLE)
                                                type = image.mimeType
                                                putExtra(Intent.EXTRA_TITLE, name)
                                            })
                                        }
                                    }
                                }
                            }) { Text("保存") }
                        }
                    }
                    HorizontalPager(
                        state = pager,
                        modifier = Modifier.weight(1f).onSizeChanged { viewport = it },
                        userScrollEnabled = scale == 1f,
                        key = { photos[it].blockIndex },
                    ) { index ->
                        val selected = index == pager.currentPage
                        val image = if (selected) current else loader.cached(photos[index].url)
                        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                            if (image != null) {
                                val gestures = if (selected) {
                                    val state = rememberTransformableState { centroid, zoom, pan, _ ->
                                        val next = (scale * zoom).coerceIn(1f, 5f)
                                        val center = Offset(viewport.width / 2f, viewport.height / 2f)
                                        val focus = if (centroid.isSpecified) centroid - center else Offset.Zero
                                        transform(next, focus + (offset - focus) * (next / scale) + pan)
                                    }
                                    Modifier.transformable(state, canPan = { scale > 1f }).pointerInput(photo.blockIndex) {
                                        detectTapGestures(onDoubleTap = { point ->
                                            val center = Offset(size.width / 2f, size.height / 2f)
                                            if (scale > 1f) transform(1f, Offset.Zero) else transform(2.5f, (center - point) * 1.5f)
                                        })
                                    }
                                } else Modifier
                                Box(Modifier.fillMaxSize().then(gestures)) {
                                    Image(
                                        image.bitmap.asImageBitmap(), photos[index].label,
                                        Modifier.fillMaxSize().graphicsLayer {
                                            if (selected) {
                                                scaleX = scale
                                                scaleY = scale
                                                translationX = offset.x
                                                translationY = offset.y
                                            }
                                        },
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                            } else if (selected && result?.isFailure == true) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("配图加载失败")
                                    TextButton(onClick = { attempt += 1 }) { Text("重试") }
                                }
                            } else {
                                CircularProgressIndicator()
                            }
                        }
                    }
                    Text(photo.label, Modifier.align(Alignment.CenterHorizontally).padding(horizontal = 16.dp), maxLines = 2)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("上一张") }
                        TextButton(enabled = current != null && scale > 1f, onClick = { transform(scale / 1.5f, offset / 1.5f) }) { Text("缩小") }
                        TextButton(enabled = current != null && scale < 5f, onClick = { transform(scale * 1.5f, offset * 1.5f) }) { Text("放大") }
                        TextButton(enabled = pager.currentPage < photos.lastIndex, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text("下一张") }
                    }
                }
            }
        }
    }
}
