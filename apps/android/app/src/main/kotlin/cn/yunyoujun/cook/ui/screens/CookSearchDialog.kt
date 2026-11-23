package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedSuggestionChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.search.CookQueryParser
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.state.CookSessionController

private val searchExamples = listOf("番茄炒蛋", "鸡蛋", "冰箱里有土豆和鸡蛋")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CookSearchDialog(
    snapshot: CookCatalogSnapshot,
    sessionController: CookSessionController,
    container: AppContainer,
    onClose: () -> Unit,
) {
    val preferences = container.preferencesStore
    val prefs by preferences.state.collectAsState()
    val personalCatalog by container.personalCatalogStore.state.collectAsState()
    val conditions by sessionController.conditions.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClearHistory by rememberSaveable { mutableStateOf(false) }
    var showPreview by rememberSaveable { mutableStateOf(false) }
    val query = input.trim()
    val focusRequester = remember { FocusRequester() }
    val catalog = remember(personalCatalog) { personalCatalog.toDocument() }
    val food = remember(snapshot, catalog) { catalog.applyingTo(snapshot.data.food) }
    val parser = remember(snapshot, catalog) {
        CookQueryParser(snapshot.data.query, catalog.applyingTo(snapshot.data.food, includeHidden = true), snapshot.data.aliases)
    }

    fun submit(text: String) {
        if (text.isBlank()) return
        sessionController.applyDiscoveryInput(snapshot, text, parser)?.let { conditions ->
            preferences.applySearchPreferences(conditions.avoid, conditions.noSpicy)
        }
        preferences.recordSearch(text)
        onClose()
    }

    Dialog(
        onDismissRequest = { if (showPreview) showPreview = false else onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        if (showPreview) {
            CookSearchIntentReview(
                initialText = query,
                snapshot = snapshot,
                sessionController = sessionController,
                container = container,
                onDismiss = { showPreview = false },
                onApplied = onClose,
            )
            return@Dialog
        }
        val focus = LocalFocusManager.current
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    title = { Text("搜索找菜") },
                    navigationIcon = { TextButton(onClick = onClose) { Text("返回") } },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(300) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    label = { Text("菜名、食材或一句话") },
                    trailingIcon = {
                        if (input.isNotEmpty()) TextButton(onClick = { input = "" }) { Text("清空") }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                )
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (query.isEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            searchExamples.forEach { example ->
                                ElevatedSuggestionChip(onClick = { submit(example) }, label = { Text(example) })
                            }
                        }
                        if (prefs.recentSearches.isNotEmpty()) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("最近搜索", style = MaterialTheme.typography.titleSmall)
                                TextButton(onClick = { confirmClearHistory = true }) { Text("清空搜索历史") }
                            }
                            prefs.recentSearches.forEach { entry ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = {
                                            input = entry
                                            focusRequester.requestFocus()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(entry, Modifier.fillMaxWidth())
                                    }
                                    TextButton(
                                        onClick = { preferences.removeSearch(entry) },
                                        modifier = Modifier.semantics { contentDescription = "删除搜索记录：$entry" },
                                    ) { Text("删除") }
                                }
                            }
                        }
                    } else {
                        val ingredients = remember(food, query, conditions.selectedStuff) {
                            (food.vegetable + food.meat + food.staple).asSequence().filter {
                                it.name !in conditions.selectedStuff && (query in it.name || it.alias?.contains(query) == true)
                            }.map { it.name }.distinct().take(2).toList()
                        }
                        val dishes = remember(snapshot, query, ingredients.size) {
                            snapshot.data.recipes.asSequence().map { it.name }.filter { query in it }
                                .distinct().take(3 - ingredients.size).toList()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { submit(query) }) { Text("搜索") }
                            TextButton(onClick = {
                                focus.clearFocus()
                                showPreview = true
                            }) { Text("预览条件") }
                        }
                        ingredients.forEach { name ->
                            TextButton(onClick = {
                                sessionController.addDiscoveryIngredient(name)
                                onClose()
                            }) {
                                Text("添加$name")
                            }
                        }
                        dishes.forEach { name ->
                            TextButton(onClick = {
                                input = name
                                focusRequester.requestFocus()
                            }) { Text(name) }
                        }
                    }
                }
            }
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("清空这台设备上的搜索历史？") },
            confirmButton = {
                TextButton(onClick = {
                    preferences.clearSearches()
                    confirmClearHistory = false
                }) { Text("清空搜索历史") }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("取消") } },
        )
    }
}
