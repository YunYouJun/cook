package cn.yunyoujun.cook.ui.screens

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.ui.components.CookSceneViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val COOK_POT = "CookPot"
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
    onRotationChanged: (Boolean) -> Unit,
    onPick: (String) -> Unit,
    resultCount: Int,
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
    val nodes = remember(ingredients, hidden) {
        ingredients.orEmpty()
            .map { it.copy(name = normalize(it.name)) }
            .filterNot { it.name in hidden }
            .associate { it.node to it.name }
    }
    val targets = remember(nodes) { nodes + (COOK_POT to COOK_POT) }
    var reset by rememberSaveable { mutableIntStateOf(0) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            if (ingredients == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp))
            } else CookSceneViewport(
                asset = "cook/scenes/cloud-kitchen.glb",
                description = "云厨房场景",
                allowRotation = allowRotation,
                resetCamera = reset,
                ingredientNodes = targets,
                selectedIngredients = selected,
                onPick = { target ->
                    if (target == COOK_POT) {
                        if (resultCount > 0) onShowResults()
                    } else {
                        onPick(target)
                    }
                },
                modifier = Modifier.fillMaxWidth().height(276.dp),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = allowRotation,
                        onClick = { onRotationChanged(!allowRotation) },
                        label = { Text("旋转视角") },
                    )
                    if (allowRotation) {
                        TextButton(onClick = { reset++ }) { Text("重置视角") }
                    }
                }
                Text("台面食材", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    nodes.values.distinct().forEach { name ->
                        FilterChip(selected = name in selected, onClick = { onPick(name) }, label = { Text(name) })
                    }
                }
                Button(onClick = onShowResults, enabled = resultCount > 0, modifier = Modifier.fillMaxWidth()) {
                    Text("查看 $resultCount 道菜")
                }
            }
        }
    }
}
