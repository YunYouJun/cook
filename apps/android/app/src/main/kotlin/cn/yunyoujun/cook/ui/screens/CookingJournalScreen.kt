package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.local.CookingRecord
import cn.yunyoujun.cook.data.local.CookingJournalStore
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.ShowcaseAssetLibrary
import cn.yunyoujun.cook.ui.components.rememberCookSceneCamera
import cn.yunyoujun.cook.ui.state.rememberLocalCalendarClock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun CookingJournalScreen(
    store: CookingJournalStore,
    showcaseAssets: ShowcaseAssetLibrary,
    recipeId: JournalRecipeId?,
    recipeTitle: (JournalRecipeId) -> String?,
    onBack: () -> Unit,
    onOpenRecipe: (JournalRecipeId) -> Unit,
) {
    val journal by store.state.collectAsState()
    var learnedOnly by rememberSaveable { mutableStateOf(false) }
    var gallery by rememberSaveable { mutableStateOf(false) }
    var showcaseId by rememberSaveable { mutableStateOf<String?>(null) }
    var featuredId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingModelId by rememberSaveable { mutableStateOf<String?>(null) }
    val showcaseStates = rememberSaveableStateHolder()
    val listState = rememberLazyListState()
    val galleryListState = rememberLazyListState()
    var removalId by rememberSaveable { mutableStateOf<String?>(null) }
    var removingLearned by rememberSaveable { mutableStateOf(false) }
    var removalFailed by rememberSaveable { mutableStateOf(false) }
    val zone = rememberLocalCalendarClock().zone
    val entries = remember(journal, learnedOnly, recipeId, zone) {
        journal.entries(learnedOnly, zone).filter { recipeId == null || it.record.recipeId == recipeId }
    }
    val days = remember(entries, zone) {
        entries.groupBy { Instant.ofEpochMilli(it.record.dateMs).atZone(zone).toLocalDate() }
    }
    val showingGallery = learnedOnly && gallery
    val exhibits = if (showingGallery) entries.mapNotNull { entry ->
        val record = entry.record
        val id = record.recipeId
        val name = recipeTitle(id) ?: return@mapNotNull null
        if (id is JournalRecipeId.Catalog && id.id !in showcaseAssets.dishes) return@mapNotNull null
        record.copy(recipeName = name)
    } else emptyList()
    val featured = exhibits.firstOrNull { it.id == featuredId } ?: exhibits.firstOrNull()
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormat = remember(zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(zone) }

    val learned = journal.learned.firstOrNull { it.id == showcaseId }
    val learnedTitle = learned?.let { recipeTitle(it.recipeId) }
    LaunchedEffect(featured?.id, learnedTitle) {
        if (viewingModelId != featured?.id) viewingModelId = null
        if (learnedTitle == null) showcaseId = null
    }
    val badgeExhibit = learned?.let { record -> learnedTitle?.let { record.copy(recipeName = it) } }
    val openExhibit = badgeExhibit ?: featured?.takeIf { viewingModelId == it.id }
    if (openExhibit != null) {
        showcaseStates.SaveableStateProvider(openExhibit.id) {
            val camera = rememberCookSceneCamera()
            val packs = showcaseAssets.packsFor(openExhibit)
            if (badgeExhibit != null) {
                LearnedDishShowcase(openExhibit, packs, camera, onBack = { showcaseId = null })
            } else {
                LearnedDishModelScreen(openExhibit, packs, camera, onBack = { viewingModelId = null })
            }
        }
        return
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("做菜足迹") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
    }) { inner ->
        LazyColumn(
            state = if (showingGallery) galleryListState else listState,
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!store.loadFailed && !learnedOnly) {
                item(key = "footprint-overview") {
                    val cookedCount = if (recipeId == null) journal.cooked.size else journal.cooked.count { it.recipeId == recipeId }
                    val learnedCount = if (recipeId == null) journal.learned.size else journal.learned.count { it.recipeId == recipeId }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (recipeId == null) "累计足迹" else recipeTitle(recipeId) ?: "这道菜的足迹",
                                modifier = Modifier.semantics { heading() },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text("做过 $cookedCount 次")
                                Text("学会 $learnedCount 道")
                            }
                            Text("仅此设备", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !learnedOnly, onClick = {
                        learnedOnly = false
                        viewingModelId = null
                    }, label = { Text("全部足迹") })
                    FilterChip(selected = learnedOnly, onClick = { learnedOnly = true }, label = { Text("我的拿手菜") })
                }
            }
            if (learnedOnly) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !gallery, onClick = {
                            gallery = false
                            viewingModelId = null
                        }, label = { Text("徽章册") })
                        FilterChip(selected = gallery, onClick = { gallery = true }, label = { Text("展柜") })
                    }
                }
            }
            if (store.loadFailed) {
                item { Text("做菜足迹暂时无法读取，原始记录已保留。") }
            } else if (showingGallery) {
                if (featured == null) {
                    item { Text("还没有可展示的拿手菜。") }
                } else {
                    stickyHeader(key = "featured-dish-picker") {
                        Surface(Modifier.fillMaxWidth()) {
                            FeaturedDishPicker(exhibits, featured) { selected ->
                                featuredId = selected.id
                                viewingModelId = null
                            }
                        }
                    }
                    item("featured-dish-content") {
                        key(featured.id) {
                            LearnedDishShowcaseContent(
                                record = featured,
                                packs = showcaseAssets.packsFor(featured),
                                onOpenModel = {
                                    featuredId = featured.id
                                    viewingModelId = featured.id
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            } else if (entries.isEmpty()) {
                item { Text(if (learnedOnly) "还没有标记学会的菜。" else "还没有做菜足迹。") }
            }
            if (!showingGallery) days.forEach { (day, dayEntries) ->
                item(key = "day-$day") {
                    Text(
                        dateFormat.format(day),
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(dayEntries, key = { it.record.id }) { entry ->
                    val record = entry.record
                    val title = recipeTitle(record.recipeId)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val catalogId = record.recipeId as? JournalRecipeId.Catalog
                            val displayName = title ?: record.recipeName
                            if (learnedOnly) LearnedDishArtwork(catalogId?.id, displayName)
                            Text(displayName, style = MaterialTheme.typography.titleMedium)
                            val status = when {
                                learnedOnly -> "首次学会"
                                entry.learned && entry.cookedId != null -> "做过 · 已学会"
                                entry.learned -> "已学会"
                                else -> "做过"
                            }
                            Text("$status · ${timeFormat.format(Instant.ofEpochMilli(record.dateMs))}")
                            if (title == null) {
                                Text(
                                    if (record.recipeId is JournalRecipeId.Personal) "私人菜谱已删除或暂不可用" else "原菜谱暂不可用",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (recipeId == null && title != null) {
                                    TextButton(onClick = { onOpenRecipe(record.recipeId) }) { Text("查看菜谱") }
                                }
                                entry.cookedId?.let { cookedId ->
                                    TextButton(onClick = {
                                        removingLearned = false
                                        removalFailed = false
                                        removalId = cookedId
                                    }) { Text("删除做过记录") }
                                }
                                entry.learnedId?.let { learnedId ->
                                    if (learnedOnly && title != null) {
                                        TextButton(onClick = { showcaseId = record.id }) { Text("查看成就") }
                                    }
                                    TextButton(onClick = {
                                        removingLearned = true
                                        removalFailed = false
                                        removalId = learnedId
                                    }) {
                                        Text("取消学会标记")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    removalId?.let { id ->
        AlertDialog(
            onDismissRequest = { removalId = null },
            title = { Text(if (removingLearned) "取消学会标记？" else "删除这次做过记录？") },
            text = if (removalFailed) {
                { Text("足迹未移除，请重试。", color = MaterialTheme.colorScheme.error) }
            } else null,
            confirmButton = {
                TextButton(onClick = {
                    runCatching {
                        if (removingLearned) store.removeLearned(id) else store.removeCooked(id)
                    }.onSuccess { removalId = null }.onFailure { removalFailed = true }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removalId = null }) { Text("取消") } },
        )
    }
}

private fun ShowcaseAssetLibrary.packsFor(record: CookingRecord) =
    dishes[(record.recipeId as? JournalRecipeId.Catalog)?.id].orEmpty()

@Composable
private fun FeaturedDishPicker(dishes: List<CookingRecord>, selected: CookingRecord, onSelect: (CookingRecord) -> Unit) {
    if (dishes.size == 1) {
        Text(selected.recipeName, style = MaterialTheme.typography.titleMedium)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("展出菜品 · ${selected.recipeName}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            dishes.forEach { record ->
                DropdownMenuItem(text = { Text(record.recipeName) }, onClick = {
                    expanded = false
                    onSelect(record)
                })
            }
        }
    }
}
