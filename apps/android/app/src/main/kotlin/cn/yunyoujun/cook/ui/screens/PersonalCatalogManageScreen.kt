package cn.yunyoujun.cook.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.sync.CatalogCategory
import cn.yunyoujun.cook.core.sync.CatalogEntry
import cn.yunyoujun.cook.core.sync.PersonalCatalogException
import cn.yunyoujun.cook.core.sync.itemsIn
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.state.PersonalCatalogTransferViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonalCatalogManageScreen(snapshot: CookCatalogSnapshot, container: AppContainer, onBack: () -> Unit) {
    val store = container.personalCatalogStore
    val state = store.state.collectAsStateWithLifecycle().value
    val transfer = viewModel { PersonalCatalogTransferViewModel(store) }
    val resolver = LocalContext.current.applicationContext.contentResolver
    val focus = LocalFocusManager.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { transfer.export(resolver, it) }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { transfer.read(resolver, it) }
    }
    var category by rememberSaveable { mutableStateOf(CatalogCategory.VEGETABLE) }
    var query by rememberSaveable { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val term = query.trim()
    val food = snapshot.data.food
    val custom = remember(state.customEntries, category, term) {
        state.customEntries.filter { it.category == category && it.name.contains(term, ignoreCase = true) }
    }
    val builtIn = remember(food, category, term) {
        food.itemsIn(category).filter {
            it.name.contains(term, ignoreCase = true) || it.label?.contains(term, ignoreCase = true) == true ||
                it.alias?.contains(term, ignoreCase = true) == true
        }
    }
    val hidden = remember(state.hiddenEntries) { state.hiddenEntries.toSet() }

    LaunchedEffect(transfer.message, transfer.pending) {
        if (transfer.pending == null) {
            transfer.message?.let { status = it }
            transfer.clearMessage()
        }
    }
    BackHandler(enabled = creating) { creating = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人食材与厨具词典") },
                navigationIcon = {
                    TextButton(onClick = { if (creating) creating = false else onBack() }) { Text("返回") }
                },
                actions = {
                    if (!creating) {
                        TextButton(onClick = {
                            focus.clearFocus()
                            creating = true
                        }) { Text("添加") }
                    }
                },
            )
        },
    ) { padding ->
        if (creating) {
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                CatalogEntryEditor(
                    initialCategory = category,
                    onDismiss = { creating = false },
                    onAdd = { name, selected ->
                        val normalized = if (selected == CatalogCategory.TOOLS) {
                            name.trim()
                        } else {
                            snapshot.data.normalizer.normalize(name)
                        }
                        val id = CatalogEntry(selected, normalized).id
                        val alreadyBuiltIn = CatalogCategory.entries.any { group ->
                            food.itemsIn(group).any { CatalogEntry(group, it.name).id == id }
                        }
                        if (alreadyBuiltIn) {
                            Result.failure(PersonalCatalogException.Duplicate())
                        } else {
                            store.addCustomEntry(normalized, selected).onSuccess {
                                category = selected
                                query = ""
                                status = null
                                creating = false
                            }
                        }
                    },
                )
            }
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding).testTag("personalCatalog.entries"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索当前分类") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                CatalogCategoryChoices(category) { category = it }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !transfer.working,
                        onClick = {
                            runCatching { save.launch("cook-personal-catalog.json") }
                                .onFailure { status = "无法打开系统文件选择器。" }
                        },
                    ) {
                        Text("导出词典 JSON")
                    }
                    OutlinedButton(
                        enabled = !transfer.working,
                        onClick = {
                            runCatching { open.launch(arrayOf("application/json", "text/plain", "*/*")) }
                                .onFailure { status = "无法打开系统文件选择器。" }
                        },
                    ) { Text("导入词典 JSON") }
                }
            }
            status?.let { item { Text(it) } }
            if (custom.isNotEmpty()) {
                item { Text("自定义词条", style = MaterialTheme.typography.titleMedium) }
                items(custom, key = { "custom:${it.id}" }) { entry ->
                    CatalogEntryRow(entry.name, "移除", "移除${entry.name}") {
                        runCatching { store.removeCustomEntry(entry.id) }
                            .onFailure { status = it.message ?: "无法移除词条。" }
                    }
                }
            }
            if (builtIn.isNotEmpty()) {
                item {
                    Text("内置词条", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "隐藏的条目不会出现在选择列表中，已有菜谱和冰箱记录会保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(builtIn, key = { "builtin:${category.rawValue}:${it.name}" }) { item ->
                    val isHidden = CatalogEntry(category, item.name) in hidden
                    val action = if (isHidden) "恢复" else "隐藏"
                    val title = listOf(item.emoji, item.label ?: item.name).filter(String::isNotBlank).joinToString(" ")
                    CatalogEntryRow(title, action, "$action${item.name}") {
                        runCatching { store.toggleHiddenEntry(category, item.name) }
                            .onFailure { status = it.message ?: "无法更新内置词条。" }
                    }
                }
            }
            if (custom.isEmpty() && builtIn.isEmpty()) {
                item { Text(if (term.isEmpty()) "当前分类没有词条" else "没有符合搜索的词条") }
            }
        }
    }

    transfer.pending?.let { incoming ->
        val currentIds = state.customEntries.mapTo(HashSet()) { it.id }
        val hiddenIds = state.hiddenEntries.mapTo(HashSet()) { it.id }
        val newEntries = incoming.items.count { it.id !in currentIds }
        val newHidden = incoming.hidden.count { it.id !in hiddenIds }
        AlertDialog(
            onDismissRequest = transfer::cancel,
            title = { Text("合并食材和厨具？") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("将添加 $newEntries 个自定义条目和 $newHidden 个隐藏偏好。")
                    Text("重复名称沿用本机分类。已有菜谱和忌口会保留。")
                    transfer.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(enabled = !transfer.working, onClick = { transfer.confirm(state) }) {
                    Text(if (transfer.working) "正在合并…" else "确认合并")
                }
            },
            dismissButton = { TextButton(enabled = !transfer.working, onClick = transfer::cancel) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogCategoryChoices(category: CatalogCategory, onChange: (CatalogCategory) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CatalogCategory.entries.forEach { option ->
            FilterChip(
                selected = option == category,
                onClick = { onChange(option) },
                label = { Text(option.title) },
            )
        }
    }
}

@Composable
private fun CatalogEntryRow(title: String, action: String, actionDescription: String, onClick: () -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            TextButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = actionDescription }) {
                Text(action)
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun CatalogEntryEditor(
    initialCategory: CatalogCategory,
    onDismiss: () -> Unit,
    onAdd: (String, CatalogCategory) -> Result<CatalogEntry>,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(initialCategory) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("添加自定义词条", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    error = null
                },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = error != null,
            )
            CatalogCategoryChoices(category) { category = it }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Button(
                    enabled = name.isNotBlank(),
                    onClick = { onAdd(name, category).onFailure { error = it.message ?: "无法添加词条。" } },
                ) { Text("添加词条") }
            }
        }
    }
}
