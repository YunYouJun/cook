package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.core.model.FoodCatalog
import cn.yunyoujun.cook.core.model.Pantry
import cn.yunyoujun.cook.core.recommend.StuffNormalizer
import cn.yunyoujun.cook.data.local.DevicePreferencesSnapshot
import cn.yunyoujun.cook.data.local.DevicePreferencesStore
import cn.yunyoujun.cook.ui.components.TagFlowChips

internal enum class CookingPreference(val title: String) {
    PANTRY("家里常备"),
    AVOID("忌口"),
}

@Composable
internal fun CookingPreferenceRows(
    prefs: DevicePreferencesSnapshot,
    onOpen: (CookingPreference) -> Unit,
) {
    fun summary(names: List<String>, empty: String): String = when {
        names.isEmpty() -> empty
        names.size <= 2 -> names.joinToString("、")
        else -> "${names.take(2).joinToString("、")}等 ${names.size} 种"
    }

    SettingsNavRow(CookingPreference.PANTRY.title, summary(prefs.pantry, "未选择")) {
        onOpen(CookingPreference.PANTRY)
    }
    SettingsNavRow(CookingPreference.AVOID.title, summary(prefs.avoid, "无")) {
        onOpen(CookingPreference.AVOID)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CookingPreferenceEditor(
    page: CookingPreference,
    food: FoodCatalog,
    normalizer: StuffNormalizer,
    pantry: Pantry,
    prefs: DevicePreferencesSnapshot,
    store: DevicePreferencesStore,
    onBack: () -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(page.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    when (page) {
                        CookingPreference.PANTRY -> TextButton(
                            onClick = { store.setPantry(pantry.defaults) },
                            enabled = prefs.pantry.toSet() != pantry.defaults.toSet(),
                        ) { Text("恢复默认") }
                        CookingPreference.AVOID -> TextButton(
                            onClick = { store.setAvoid(emptyList()) },
                            enabled = prefs.avoid.isNotEmpty(),
                        ) { Text("清空忌口") }
                    }
                },
            )
        },
    ) { padding ->
        val content = Modifier.fillMaxSize().padding(padding)
        when (page) {
            CookingPreference.PANTRY -> PantryOptions(pantry, prefs.pantry, store::togglePantryItem, content)
            CookingPreference.AVOID -> AvoidOptions(food, normalizer, prefs.avoid, store::toggleAvoidItem, content)
        }
    }
}

@Composable
private fun PantryOptions(
    pantry: Pantry,
    selected: List<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier,
) {
    val (aromatics, seasonings) = pantry.options.partition { it in setOf("葱", "姜", "蒜") }
    val selectedItems = selected.toSet()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("已备 ${selected.size} 种", style = MaterialTheme.typography.titleSmall)
            Text("家里常备的调料不算缺少的食材。", style = MaterialTheme.typography.bodyMedium)
        }
        if (seasonings.isNotEmpty()) {
            item { Text("调料", style = MaterialTheme.typography.titleSmall) }
            item { TagFlowChips(seasonings, selectedItems, onToggle) }
        }
        if (aromatics.isNotEmpty()) {
            item { Text("葱姜蒜", style = MaterialTheme.typography.titleSmall) }
            item { TagFlowChips(aromatics, selectedItems, onToggle) }
        }
    }
}

@Composable
private fun AvoidOptions(
    food: FoodCatalog,
    normalizer: StuffNormalizer,
    selected: List<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val query = input.trim()
    val canonical = normalizer.normalize(query)
    val groups = listOf("蔬菜" to food.vegetable, "肉和蛋" to food.meat, "主食" to food.staple)
        .map { (title, entries) ->
            val names = entries.filter {
                query.isEmpty() || it.name == canonical || it.name.contains(query, ignoreCase = true) ||
                    it.alias?.contains(query, ignoreCase = true) == true
            }.map { it.name }.distinct()
            title to names
        }
        .filter { (_, names) -> names.isNotEmpty() }
    val selectedItems = selected.toSet()
    val focus = LocalFocusManager.current
    val gridState = rememberLazyGridState()

    Column(modifier) {
        OutlinedTextField(
            value = input,
            onValueChange = {
                if (it.trim() != query) gridState.requestScrollToItem(0)
                input = it
            },
            label = { Text("搜索食材或别名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            trailingIcon = {
                if (input.isNotEmpty()) {
                    TextButton(onClick = {
                        input = ""
                        gridState.requestScrollToItem(0)
                    }) {
                        Text("清除搜索")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            state = gridState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            item("selected", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已选忌口 · ${selected.size} 种", style = MaterialTheme.typography.titleSmall)
                    if (selected.isEmpty()) Text("还没有设置忌口", style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(selected, key = { "selected.$it" }) { name ->
                InputChip(
                    selected = true,
                    onClick = { onToggle(name) },
                    label = { Text(name) },
                    trailingIcon = { Text("×") },
                    modifier = Modifier.semantics { contentDescription = "移除忌口 $name" },
                )
            }
            groups.forEach { (title, names) ->
                item(title, span = { GridItemSpan(maxLineSpan) }) {
                    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(names, key = { "$title.$it" }) { name ->
                    FilterChip(
                        selected = name in selectedItems,
                        onClick = { onToggle(name) },
                        label = { Text(name) },
                    )
                }
            }
            if (query.isNotEmpty() && groups.isEmpty()) {
                item("empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text("没有找到「$query」", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
