package cn.yunyoujun.cook.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.platform.CLOUD_KITCHEN_POT
import cn.yunyoujun.cook.platform.CookSceneCamera
import cn.yunyoujun.cook.ui.components.CookSceneSource
import cn.yunyoujun.cook.ui.components.CookSceneViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val kitchenJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class KitchenManifest(val version: Int, val ingredients: List<KitchenIngredient>)

@Serializable
private data class KitchenIngredient(val node: String, val name: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CloudKitchenCard(
    selected: Set<String>,
    hidden: Set<String>,
    normalize: (String) -> String,
    allowRotation: Boolean,
    camera: MutableState<CookSceneCamera>,
    onRotationChanged: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    onShowAllIngredients: () -> Unit,
    recipes: List<Recipe>,
    menuAvailable: Boolean,
    onShowResults: () -> Unit,
) {
    val assets = LocalContext.current.assets
    val ingredients by produceState<List<KitchenIngredient>?>(initialValue = null, assets) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val json = assets.open("cook/scenes/kitchen.json").bufferedReader().use { it.readText() }
                kitchenJson.decodeFromString<KitchenManifest>(json).takeIf { it.version == 1 }?.ingredients
            }.getOrNull().orEmpty()
        }
    }
    val nodes = remember(ingredients, hidden, normalize) {
        ingredients.orEmpty()
            .map { it.copy(name = normalize(it.name)) }
            .filterNot { it.name in hidden }
            .associate { it.node to it.name }
    }
    val targets = remember(nodes) { nodes + (CLOUD_KITCHEN_POT to CLOUD_KITCHEN_POT) }
    val featured = remember(nodes) { nodes.values.toSet() }
    val resultCount = recipes.size
    var cooking by remember(selected, recipes, menuAvailable) { mutableStateOf(false) }
    var needsAdjustment by remember(selected, recipes) { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val showResults by rememberUpdatedState(onShowResults)

    LaunchedEffect(allowRotation) {
        if (!allowRotation) camera.value = CookSceneCamera.Initial
    }
    LaunchedEffect(cooking) {
        progress.snapTo(0f)
        if (cooking) {
            progress.animateTo(1f, tween(durationMillis = 720, easing = LinearEasing))
            cooking = false
            showResults()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { cooking = false }

    fun cook() {
        if (!menuAvailable) return
        if (resultCount == 0) needsAdjustment = true
        else if (!cooking) cooking = true
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (ingredients == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
            } else CookSceneViewport(
                source = CookSceneSource.Bundled("cook/scenes/cloud-kitchen.glb"),
                description = "云厨房场景",
                allowRotation = allowRotation,
                camera = camera,
                ingredientNodes = targets,
                selectedIngredients = selected,
                cookingProgress = { progress.value },
                preparingMenu = cooking,
                onPick = { target ->
                    if (target == CLOUD_KITCHEN_POT) cook() else onPick(target)
                },
                modifier = Modifier.fillMaxWidth().height(276.dp),
                unavailableContent = { Text("暂时无法显示 3D，可继续用下方食材篮找菜。", Modifier.padding(16.dp)) },
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (needsAdjustment) {
                    Text("这些条件暂时没找到菜，调整一下再试试。",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = allowRotation,
                        onClick = { onRotationChanged(!allowRotation) },
                        label = { Text("旋转视角") },
                    )
                    if (allowRotation) {
                        TextButton(onClick = { camera.value = CookSceneCamera.Initial }) { Text("重置视角") }
                    }
                }
                Text("食材篮 · 已选 ${selected.size} 样", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    featured.forEach { name ->
                        FilterChip(selected = name in selected, onClick = { onPick(name) }, label = { Text(name) })
                    }
                }
                val extra = selected - featured
                if (extra.isNotEmpty()) {
                    Text("其他已选食材（点击移除）", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        extra.forEach { name ->
                            InputChip(selected = true, onClick = { onPick(name) }, label = { Text(name) })
                        }
                    }
                }
                TextButton(onClick = {
                    cooking = false
                    onShowAllIngredients()
                }) { Text("全部食材") }
                Button(onClick = ::cook, enabled = menuAvailable && resultCount > 0 && !cooking, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (cooking) "正在准备菜单…" else "查看 $resultCount 道菜",
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}
