package cn.yunyoujun.cook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cn.yunyoujun.cook.platform.CookSceneCamera
import cn.yunyoujun.cook.platform.CookSceneStatus
import cn.yunyoujun.cook.platform.CookSceneView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val FIRST_FRAME_TIMEOUT_MS = 12_000L

internal sealed interface CookSceneSource {
    data class Bundled(val path: String) : CookSceneSource
    data class Installed(val file: File) : CookSceneSource
}

private val cameraSaver = listSaver<CookSceneCamera, Double>(
    save = { listOf(it.heading, it.elevation, it.magnification) },
    restore = { CookSceneCamera(it[0], it[1], it[2]) },
)

@Composable
internal fun rememberCookSceneCamera(): MutableState<CookSceneCamera> =
    rememberSaveable(stateSaver = cameraSaver) { mutableStateOf(CookSceneCamera.Initial) }

@Composable
internal fun CookSceneViewport(
    source: CookSceneSource,
    description: String,
    allowRotation: Boolean,
    camera: MutableState<CookSceneCamera>,
    modifier: Modifier = Modifier,
    ingredientNodes: Map<String, String> = emptyMap(),
    selectedIngredients: Set<String> = emptySet(),
    cookingProgress: () -> Float = { 0f },
    preparingMenu: Boolean = false,
    onPick: (String) -> Unit = {},
    onTurnModel: ((Int) -> Unit)? = null,
    onResetModel: (() -> Unit)? = null,
    unavailableContent: @Composable () -> Unit = { Text("暂时无法显示 3D") },
    controls: @Composable (Boolean) -> Unit = {},
) {
    var attempt by remember(source, ingredientNodes) { mutableIntStateOf(0) }
    key(source, ingredientNodes, attempt) {
        val assets = LocalContext.current.assets
        val model by produceState<ViewportModel>(ViewportModel.Loading, assets) {
            value = withContext(Dispatchers.IO) {
                try {
                    val bytes = when (source) {
                        is CookSceneSource.Bundled -> assets.open(source.path).use { it.readBytes() }
                        is CookSceneSource.Installed -> source.file.readBytes()
                    }
                    ViewportModel.Loaded(bytes)
                } catch (_: IOException) {
                    ViewportModel.Unavailable
                }
            }
        }
        var status by remember { mutableStateOf(CookSceneStatus.Loading) }
        if (status == CookSceneStatus.Loading && model != ViewportModel.Unavailable) {
            val lifecycle = LocalLifecycleOwner.current.lifecycle
            LaunchedEffect(lifecycle) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    delay(FIRST_FRAME_TIMEOUT_MS)
                    if (status == CookSceneStatus.Loading) status = CookSceneStatus.Unavailable
                }
            }
        }
        val background = MaterialTheme.colorScheme.surfaceContainerLow
        val sceneProgress = cookingProgress()

        Box(
            modifier = modifier.background(background).clipToBounds().semantics {
                contentDescription = description
                if (status == CookSceneStatus.Ready) stateDescription = "当前角度 ${camera.value.angleDegrees} 度"
                if (status == CookSceneStatus.Ready && onTurnModel != null && onResetModel != null) {
                    customActions = listOf(
                        CustomAccessibilityAction("左转 30 度") {
                            onTurnModel(-1)
                            true
                        },
                        CustomAccessibilityAction("右转 30 度") {
                            onTurnModel(1)
                            true
                        },
                        CustomAccessibilityAction("重置视角") {
                            onResetModel()
                            true
                        },
                    )
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            val current = model
            if (current is ViewportModel.Loaded && status != CookSceneStatus.Unavailable) {
                key(current, ingredientNodes) {
                    AndroidView(
                        factory = { context ->
                            CookSceneView(
                                context = context,
                                model = current.bytes,
                                ingredientNodes = ingredientNodes,
                                retrying = attempt > 0,
                                initialCamera = camera.value,
                                reportCamera = { camera.value = it },
                                reportStatus = { result ->
                                    if (status != CookSceneStatus.Unavailable) status = result
                                },
                            )
                        },
                        update = {
                            it.update(
                                allowRotation, camera.value, selectedIngredients, background.toArgb(),
                                sceneProgress, preparingMenu, onPick,
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            when {
                current == ViewportModel.Unavailable || status == CookSceneStatus.Unavailable -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    ) {
                        unavailableContent()
                        TextButton(onClick = { attempt++ }) { Text("重试 3D") }
                    }
                }
                status == CookSceneStatus.Loading -> CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "正在加载 3D" },
                )
            }
        }
        controls(status == CookSceneStatus.Ready)
    }
}

private sealed interface ViewportModel {
    data object Loading : ViewportModel
    data object Unavailable : ViewportModel
    class Loaded(val bytes: ByteArray) : ViewportModel
}
