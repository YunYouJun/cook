package cn.yunyoujun.cook.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import cn.yunyoujun.cook.platform.CookSceneView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

@Composable
internal fun CookSceneViewport(
    asset: String,
    description: String,
    allowRotation: Boolean,
    resetCamera: Int,
    modifier: Modifier = Modifier,
    turnSteps: Int = 0,
    ingredientNodes: Map<String, String> = emptyMap(),
    selectedIngredients: Set<String> = emptySet(),
    onPick: (String) -> Unit = {},
) {
    val assets = LocalContext.current.assets
    val model by produceState<ViewportModel>(ViewportModel.Loading, asset, assets) {
        value = withContext(Dispatchers.IO) {
            try {
                ViewportModel.Loaded(assets.open(asset).use { it.readBytes() })
            } catch (_: IOException) {
                ViewportModel.Unavailable
            }
        }
    }
    var ready by remember(asset, ingredientNodes) { mutableStateOf<Boolean?>(null) }
    val background = MaterialTheme.colorScheme.surfaceContainerLow

    Box(
        modifier = modifier.background(background).clipToBounds().semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val current = model
        if (current is ViewportModel.Loaded && ready != false) {
            key(current, ingredientNodes) {
                AndroidView(
                    factory = { context ->
                        CookSceneView(context, current.bytes, ingredientNodes) { available -> ready = available }
                    },
                    update = { it.update(allowRotation, resetCamera, turnSteps, selectedIngredients, background.toArgb(), onPick) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        when {
            current == ViewportModel.Unavailable || ready == false -> Text("暂时无法显示 3D")
            ready == null -> CircularProgressIndicator()
        }
    }
}

private sealed interface ViewportModel {
    data object Loading : ViewportModel
    data object Unavailable : ViewportModel
    class Loaded(val bytes: ByteArray) : ViewportModel
}
