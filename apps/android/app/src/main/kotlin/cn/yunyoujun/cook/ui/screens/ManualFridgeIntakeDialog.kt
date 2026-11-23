package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.local.FridgeBatchItem
import cn.yunyoujun.cook.data.local.FridgeCategory
import cn.yunyoujun.cook.data.local.FridgeDateKind
import cn.yunyoujun.cook.data.local.PersonalCatalogState
import cn.yunyoujun.cook.data.local.ProduceStorageAdvice
import cn.yunyoujun.cook.data.local.estimateFridgeIntake
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.components.TagFlowChips
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private data class PendingFridgeItem(
    val name: String,
    val quantity: String = "",
    val daysText: String = "",
) {
    val plannedDays: Int? get() = daysText.toIntOrNull()?.takeIf { it in 1..365 }
    val valid: Boolean get() = daysText.isEmpty() || plannedDays != null
}

private val intakeSelectionSaver = listSaver<List<PendingFridgeItem>, String>(
    save = { entries -> entries.flatMap { listOf(it.name, it.quantity, it.daysText) } },
    restore = { fields -> fields.chunked(3).map { (name, quantity, daysText) -> PendingFridgeItem(name, quantity, daysText) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ManualFridgeIntakeDialog(
    snapshot: CookCatalogSnapshot,
    personalCatalog: PersonalCatalogState,
    categories: List<FridgeCategory>,
    today: LocalDate,
    automaticCategory: (String) -> String,
    onCreateCategory: (String) -> FridgeCategory,
    onDismiss: () -> Unit,
    onSave: (List<FridgeBatchItem>) -> Unit,
) {
    val normalizer = snapshot.data.normalizer
    val shelfLife = snapshot.data.shelfLife
    val catalogGroups = remember(snapshot, personalCatalog) {
        val food = personalCatalog.toDocument().applyingTo(snapshot.data.food)
        val hidden = personalCatalog.hiddenBuiltInNames
        val fruit = ProduceStorageAdvice.FRUIT_NAMES.filterNot { it in hidden }
        listOf(
            "水果" to fruit,
            "蔬菜" to food.vegetable.map { it.name }.filterNot { it in fruit },
            "肉和蛋" to food.meat.map { it.name },
            "主食" to food.staple.map { it.name },
        ).filter { (_, names) -> names.isNotEmpty() }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var catalogTab by rememberSaveable { mutableStateOf("水果") }
    var categoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingCategory by rememberSaveable { mutableStateOf(false) }
    var selections by rememberSaveable(stateSaver = intakeSelectionSaver) { mutableStateOf(emptyList()) }
    var expandedDetails by rememberSaveable { mutableStateOf<String?>(null) }
    var saveError by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val selectedNames = selections.mapTo(linkedSetOf()) { it.name }
    val selectedCategoryId = categoryId?.takeIf { id -> categories.any { it.id == id } }
    val activeTab = catalogGroups.indexOfFirst { (label, _) -> label == catalogTab }.coerceAtLeast(0)
    val searching = query.isNotBlank()
    val visibleNames = remember(query, catalogGroups, activeTab, normalizer) {
        if (searching) {
            val term = normalizer.normalize(query)
            catalogGroups.flatMap { it.second }.distinct()
                .filter { it.contains(query.trim(), ignoreCase = true) || normalizer.normalize(it).contains(term, ignoreCase = true) }
        } else {
            catalogGroups.getOrNull(activeTab)?.second.orEmpty()
        }
    }

    fun toggleName(rawName: String) {
        val name = normalizer.normalize(rawName)
        selections = if (name in selectedNames) {
            if (expandedDetails == name) expandedDetails = null
            selections.filterNot { it.name == name }
        } else {
            selections + PendingFridgeItem(name)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("放入冰箱库存") },
        text = {
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current

            fun finishInput() {
                query = ""
                focus.clearFocus()
                keyboard?.hide()
            }

            fun addInput() {
                val names = normalizer.unique(listOf(query)).filterNot { it in selectedNames }
                selections = selections + names.map { PendingFridgeItem(it) }
                finishInput()
            }

            fun selectName(name: String) {
                toggleName(name)
                if (searching) finishInput()
            }

            LazyColumn(
                modifier = Modifier.heightIn(max = 550.dp).testTag("manual-fridge-intake-form"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索或输入食材") },
                        placeholder = { Text("多个食材用逗号分隔") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (searching) addInput() }),
                        trailingIcon = {
                            TextButton(enabled = searching, onClick = ::addInput) { Text("添加") }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (selectedNames.isNotEmpty()) {
                    item {
                        Text("已选 ${selectedNames.size} 样", style = MaterialTheme.typography.titleSmall)
                        TagFlowChips(selectedNames.toList(), selectedNames, ::selectName)
                    }
                }
                if (!searching && catalogGroups.isNotEmpty()) {
                    item {
                        SecondaryScrollableTabRow(selectedTabIndex = activeTab, edgePadding = 0.dp) {
                            catalogGroups.forEachIndexed { index, (label, _) ->
                                Tab(selected = index == activeTab, onClick = { catalogTab = label }, text = { Text(label) })
                            }
                        }
                    }
                }
                if (visibleNames.isNotEmpty()) {
                    item {
                        val activeNames = visibleNames.filter { normalizer.normalize(it) in selectedNames }.toSet()
                        TagFlowChips(visibleNames, activeNames, ::selectName)
                    }
                } else if (searching) {
                    item { Text("可点「添加」使用输入的名称。", style = MaterialTheme.typography.bodySmall) }
                }
                item {
                    val single = selections.singleOrNull()?.name
                    FridgeCategoryChoices(
                        categories, selectedCategoryId, single?.let(automaticCategory),
                        onCreate = { creatingCategory = true },
                        onSelect = { categoryId = it },
                    )
                    if (selections.size > 1 && selectedCategoryId == null) {
                        Text("每样食材分别自动归类。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (selections.isNotEmpty()) {
                    item { Text("食用时间建议", style = MaterialTheme.typography.titleSmall) }
                    items(selections, key = { it.name }) { entry ->
                        val estimate = estimateFridgeIntake(entry.name, shelfLife, entry.plannedDays)
                        val label = when (estimate.kind) {
                            FridgeDateKind.MANUAL -> "自设 ${estimate.days} 天"
                            FridgeDateKind.ADVISORY -> "建议 ${estimate.days} 天内吃完"
                            else -> "通用估算 ${estimate.days} 天"
                        }
                        val expanded = selections.size == 1 || expandedDetails == entry.name
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                                    Text("$label · ${estimate.dateFrom(today)}", style = MaterialTheme.typography.bodySmall)
                                    if (entry.quantity.isNotBlank()) Text(entry.quantity, style = MaterialTheme.typography.bodySmall)
                                }
                                if (selections.size > 1) {
                                    TextButton(
                                        onClick = { expandedDetails = if (expanded) null else entry.name },
                                        modifier = Modifier.semantics {
                                            contentDescription = if (expanded) "收起${entry.name}的详情" else "调整${entry.name}的数量或食用时间"
                                        },
                                    ) {
                                        Text(if (expanded) "收起" else "调整")
                                    }
                                }
                            }
                            if (expanded) {
                                estimate.storageCondition?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                OutlinedTextField(
                                    value = entry.quantity,
                                    onValueChange = { value ->
                                        selections = selections.map { if (it.name == entry.name) it.copy(quantity = value) else it }
                                    },
                                    label = { Text("数量说明（选填）") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                OutlinedTextField(
                                    value = entry.daysText,
                                    onValueChange = { value ->
                                        selections = selections.map {
                                            if (it.name == entry.name) it.copy(daysText = value.filter(Char::isDigit)) else it
                                        }
                                    },
                                    label = { Text("自设食用天数（选填）") },
                                    supportingText = { if (!entry.valid) Text("请输入 1 至 365 天。") },
                                    isError = !entry.valid,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    item {
                        Text("包装食品以包装说明为准。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (saveError) Text("食材未保存，请重试。", color = MaterialTheme.colorScheme.error)
                Button(
                    enabled = !saving && selections.isNotEmpty() && selections.all { it.valid },
                    onClick = {
                        if (saving) return@Button
                        saving = true
                        val addedAt = Instant.now()
                        val entryDay = addedAt.atZone(ZoneId.systemDefault()).toLocalDate()
                        val items = selections.map { entry ->
                            val estimate = estimateFridgeIntake(entry.name, shelfLife, entry.plannedDays)
                            FridgeBatchItem(
                                ingredientName = entry.name,
                                categoryId = selectedCategoryId ?: automaticCategory(entry.name),
                                quantityText = entry.quantity.trim(),
                                addedAtMs = addedAt.toEpochMilli(),
                                expiresOnIsoDate = estimate.dateFrom(entryDay).toString(),
                                dateKind = estimate.kind,
                            )
                        }
                        runCatching { onSave(items) }.onFailure {
                            saving = false
                            saveError = true
                        }
                    },
                ) { Text(if (selections.isEmpty()) "放入" else "放入（${selections.size} 样）") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    if (creatingCategory) {
        FridgeCreateCategoryDialog(
            categories = categories,
            onCreate = onCreateCategory,
            onCreated = {
                categoryId = it.id
                creatingCategory = false
            },
            onDismiss = { creatingCategory = false },
        )
    }
}
