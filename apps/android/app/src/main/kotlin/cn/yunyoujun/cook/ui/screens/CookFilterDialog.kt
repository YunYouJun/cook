package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.RecommendationMode
import cn.yunyoujun.cook.core.recommend.Recommender
import cn.yunyoujun.cook.data.local.DevicePreferencesSnapshot
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.data.repository.CookDiscoveryScene
import cn.yunyoujun.cook.ui.components.DietaryGoalBar
import cn.yunyoujun.cook.ui.components.TagFlowChips
import cn.yunyoujun.cook.ui.state.CookSessionConditions
import cn.yunyoujun.cook.ui.state.CookSessionController

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CookFilterDialog(
    snapshot: CookCatalogSnapshot,
    food: FoodCatalog,
    conditions: CookSessionConditions,
    controller: CookSessionController,
    prefs: DevicePreferencesSnapshot,
    store: DevicePreferencesStore,
    count: Int,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    var preference by rememberSaveable { mutableStateOf<CookingPreference?>(null) }
    val listState = rememberLazyListState()
    val page = preference
    Dialog(
        onDismissRequest = { if (preference == null) onClose() else preference = null },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (page != null) {
            CookingPreferenceEditor(page, food, snapshot.data.normalizer, snapshot.data.pantry, prefs, store) {
                preference = null
            }
        } else {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                    TopAppBar(
                        title = { Text("筛选") },
                        actions = {
                            TextButton(onClick = onClear, enabled = conditions.isFilterActive(prefs)) { Text("清除") }
                            TextButton(onClick = onClose) { Text("完成") }
                        },
                    )
                },
                bottomBar = {
                    Surface(tonalElevation = 3.dp) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                "符合条件 $count 道菜",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.testTag("cook.filter.resultCount")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            if (count == 0) {
                                Text(
                                    "试试减少筛选条件，或更换食材和厨具。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding).testTag("cook.filter.options"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("method") {
                        Text("做法分类", style = MaterialTheme.typography.titleSmall)
                        TagFlowChips(
                            Recommender.METHOD_GROUPS.map { it.label },
                            conditions.methodGroups.toSet(),
                            controller::toggleMethodGroup,
                        )
                    }
                    item("difficulty") {
                        Text("难度", style = MaterialTheme.typography.titleSmall)
                        TagFlowChips(Recommender.DIFFICULTY_LEVELS, conditions.difficulty.toSet(), controller::toggleDifficulty)
                    }
                    item("tool") {
                        Text("厨具", style = MaterialTheme.typography.titleSmall)
                        TagFlowChips(food.tools.map { it.name }.distinct(), setOfNotNull(conditions.tool), controller::toggleTool)
                    }
                    item("mode") {
                        Text("匹配模式", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                RecommendationMode.LOOSE to "宽松模式",
                                RecommendationMode.STRICT to "严格模式",
                                RecommendationMode.SURVIVAL to "应急模式",
                            ).forEach { (mode, title) ->
                                FilterChip(
                                    selected = prefs.mode == mode,
                                    onClick = { store.setMode(mode) },
                                    label = { Text(title) },
                                )
                            }
                        }
                    }
                    item("diet") {
                        DietaryGoalBar(snapshot.availableDietaryGoals, conditions.dietaryGoal(prefs)) {
                            controller.selectDietaryGoal(it, store)
                        }
                    }
                    item("scene") {
                        Text("今天想做什么", style = MaterialTheme.typography.titleSmall)
                        CookDiscoverySceneChips(snapshot, conditions.discoveryScene, controller::toggleDiscoveryScene)
                    }
                    item("preferences") {
                        Text("调料和忌口", style = MaterialTheme.typography.titleSmall)
                        CookingPreferenceRows(prefs, onOpen = { preference = it })
                        val noSpicy = prefs.noSpicy || conditions.noSpicy
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = noSpicy,
                                role = Role.Switch,
                                onValueChange = { controller.setNoSpicy(it, store) },
                            ).padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("不吃辣", style = MaterialTheme.typography.bodyMedium)
                            Switch(checked = noSpicy, onCheckedChange = null)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CookDiscoverySceneChips(
    snapshot: CookCatalogSnapshot,
    selected: CookDiscoveryScene?,
    onToggle: (CookDiscoveryScene) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CookDiscoveryScene.entries.forEach { scene ->
            if (scene != CookDiscoveryScene.INSTITUTIONAL_LOW_GI || snapshot.institutionalLowGICatalog?.isAvailable == true) {
                FilterChip(
                    selected = selected == scene,
                    onClick = { onToggle(scene) },
                    label = { Text("${scene.emoji} ${scene.label}") },
                )
            }
        }
    }
}
