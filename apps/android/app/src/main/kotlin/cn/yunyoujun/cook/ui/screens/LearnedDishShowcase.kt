package cn.yunyoujun.cook.ui.screens

import android.graphics.BitmapFactory
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cn.yunyoujun.cook.data.local.CookingRecord
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.ShowcaseKind
import cn.yunyoujun.cook.data.local.ShowcasePackStore
import cn.yunyoujun.cook.platform.CookSceneCamera
import cn.yunyoujun.cook.ui.components.CookSceneSource
import cn.yunyoujun.cook.ui.components.CookSceneViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LearnedDishShowcase(
    record: CookingRecord,
    packs: List<ShowcasePackStore>,
    camera: MutableState<CookSceneCamera>,
    onBack: () -> Unit,
) {
    var showingModel by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    if (showingModel) {
        LearnedDishModelScreen(record, packs, camera, onBack = { showingModel = false })
        return
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(record.recipeName) },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
        )
    }) { inner ->
        LearnedDishShowcaseContent(
            record = record,
            packs = packs,
            onOpenModel = { showingModel = true },
            modifier = Modifier.fillMaxWidth().padding(inner).verticalScroll(scroll).padding(16.dp),
        )
    }
}

@Composable
internal fun LearnedDishShowcaseContent(
    record: CookingRecord,
    packs: List<ShowcasePackStore>,
    onOpenModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalogId = (record.recipeId as? JournalRecipeId.Catalog)?.id
    val imagePack = packs.firstOrNull { it.asset.kind == ShowcaseKind.Image }
    val imageFile = imagePack?.state?.collectAsStateWithLifecycle()?.value?.installation?.file
    RevalidateShowcasePacks(packs)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LearnedDate(record)
        LearnedDishArtwork(catalogId, record.recipeName, imageFile)
        if (packs.isEmpty()) {
            OutlinedButton(onClick = onOpenModel) { Text("查看通用餐盘") }
        } else {
            Text("离线素材", style = MaterialTheme.typography.titleMedium)
            for (pack in packs) {
                ShowcasePackCard(pack, onOpenModel = onOpenModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LearnedDishModelScreen(
    record: CookingRecord,
    packs: List<ShowcasePackStore>,
    camera: MutableState<CookSceneCamera>,
    onBack: () -> Unit,
) {
    val catalogId = (record.recipeId as? JournalRecipeId.Catalog)?.id
    val imagePack = packs.firstOrNull { it.asset.kind == ShowcaseKind.Image }
    val modelPack = packs.firstOrNull { it.asset.kind == ShowcaseKind.Model }
    val imageFile = imagePack?.state?.collectAsStateWithLifecycle()?.value?.installation?.file
    val modelState = modelPack?.state?.collectAsStateWithLifecycle()?.value
    val modelFile = modelState?.installation?.file
    val source = when {
        packs.isEmpty() -> CookSceneSource.Bundled("cook/scenes/showcase-plate.glb")
        modelFile != null -> CookSceneSource.Installed(modelFile)
        else -> null
    }
    RevalidateShowcasePacks(packs)
    val checking = modelState != null &&
        (modelState.busy || (!modelState.installationChecked && modelState.error == null))
    LaunchedEffect(source, checking) {
        if (source == null && !checking) onBack()
    }
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(record.recipeName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回成就") } },
        )
    }) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LearnedDate(record)
            if (source != null) {
                ShowcaseModel(source, record.recipeName, catalogId, imageFile, camera, Modifier.weight(1f).fillMaxWidth())
            } else if (checking) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                        .semantics { contentDescription = "正在核对离线模型" },
                )
            }
        }
    }
}

@Composable
private fun LearnedDate(record: CookingRecord) {
    val learnedDate = remember(record.dateMs) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(record.dateMs))
    }
    Text("首次学会 $learnedDate", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun RevalidateShowcasePacks(packs: List<ShowcasePackStore>) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(packs, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            packs.forEach { pack ->
                launch {
                    pack.state.first { !it.busy }
                    pack.reload()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowcasePackCard(pack: ShowcasePackStore, onOpenModel: () -> Unit) {
    val context = LocalContext.current
    val state by pack.state.collectAsStateWithLifecycle()
    val asset = pack.asset
    val isModel = asset.kind == ShowcaseKind.Model
    val name = if (isModel) "模型包" else "图片包"
    val installed = state.installation
    val updateAvailable = installed != null && installed.file.name != asset.filename
    val received = state.receivedBytes
    val availableSize = Formatter.formatShortFileSize(context, asset.bytes.toLong())

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            if (installed != null) {
                val installedSize = Formatter.formatShortFileSize(context, installed.bytes.toLong())
                val status = if (isModel) "已安装" else "已下载"
                Text("$status · V${installed.version} · $installedSize", style = MaterialTheme.typography.bodySmall)
            } else {
                val size = if (pack.hasBundledModel) "随 App 提供 · $availableSize" else availableSize
                Text(size, style = MaterialTheme.typography.bodySmall)
            }
            if (received != null) {
                Text("正在下载$name", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = {
                        if (asset.bytes > 0) (received.toFloat() / asset.bytes).coerceIn(0f, 1f) else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (installed != null && isModel) {
                    OutlinedButton(onClick = onOpenModel) { Text("查看 3D") }
                }
                if (received != null) {
                    TextButton(onClick = { pack.cancel() }) { Text("取消下载$name") }
                } else if (!state.busy) {
                    if (installed == null || updateAvailable) {
                        if (pack.hasBundledModel) {
                            OutlinedButton(onClick = { pack.installBundled() }) {
                                Text(if (updateAvailable) "更新内置模型" else "安装内置模型")
                            }
                            if (state.error != null) {
                                TextButton(onClick = { pack.download() }) { Text("联网下载模型包") }
                            }
                        } else {
                            OutlinedButton(onClick = { pack.download() }) {
                                Text(if (updateAvailable) "更新$name（$availableSize）" else "下载$name")
                            }
                        }
                    }
                    if (installed != null) {
                        TextButton(onClick = { pack.remove() }) { Text("删除$name") }
                    }
                }
            }
            state.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowcaseModel(
    source: CookSceneSource,
    name: String,
    catalogId: Int?,
    imageFile: File?,
    camera: MutableState<CookSceneCamera>,
    modifier: Modifier,
) {
    val turnModel: (Int) -> Unit = { camera.value = camera.value.turn(it) }
    val resetView = { camera.value = CookSceneCamera.Initial }
    CookSceneViewport(
        source = source,
        description = "$name 3D 展示",
        allowRotation = true,
        camera = camera,
        onTurnModel = turnModel,
        onResetModel = resetView,
        modifier = modifier,
        unavailableContent = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LearnedDishArtwork(catalogId, name, imageFile)
                Text("暂时无法显示 3D")
            }
        },
        controls = { ready ->
            // Keep the TextureView size stable when its first frame becomes available.
            val visibility = if (ready) Modifier else Modifier.alpha(0f).clearAndSetSemantics { hideFromAccessibility() }
            Column(visibility, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("左右拖动旋转，双指缩放。", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = ready, onClick = { turnModel(-1) }) { Text("左转") }
                    TextButton(enabled = ready, onClick = { turnModel(1) }) { Text("右转") }
                    TextButton(enabled = ready, onClick = resetView) { Text("重置视角") }
                }
            }
        },
    )
}

@Composable
internal fun LearnedDishArtwork(catalogId: Int?, recipeName: String, imageFile: File? = null) = key(catalogId, imageFile) {
    val assets = LocalContext.current.assets
    val artwork by produceState<DishArtwork?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            imageFile?.let { BitmapFactory.decodeFile(it.path) }?.let { DishArtwork(it.asImageBitmap(), false) }
                ?: catalogId?.let { id ->
                    try {
                        assets.open("cook/showcase/$id.webp").use { BitmapFactory.decodeStream(it) }
                            ?.let { DishArtwork(it.asImageBitmap(), true) }
                    } catch (_: IOException) {
                        null
                    }
                }
        }
    }
    val installed = artwork
    if (installed != null) {
        Image(
            installed.bitmap,
            contentDescription = if (installed.isBadge) "${recipeName}徽章" else "${recipeName}配图",
            modifier = Modifier.fillMaxWidth().heightIn(max = if (installed.isBadge) 116.dp else 180.dp),
        )
    } else {
        Box(
            modifier = Modifier.fillMaxWidth().height(76.dp).semantics { contentDescription = "${recipeName}徽章" },
            contentAlignment = Alignment.Center,
        ) {
            Text("🏅", style = MaterialTheme.typography.headlineLarge)
        }
    }
}

private data class DishArtwork(val bitmap: ImageBitmap, val isBadge: Boolean)
