package cn.yunyoujun.cook.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.local.CookingJournalStore
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.LearningConfirmation
import cn.yunyoujun.cook.data.local.CookingRecord
import cn.yunyoujun.cook.ui.components.CookSceneViewport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CookingRecordActions(
    store: CookingJournalStore,
    recipeId: JournalRecipeId,
    name: String,
    onOpenJournal: () -> Unit,
) {
    val journal by store.state.collectAsState()
    val cookedToday = journal.cookedOn(recipeId, System.currentTimeMillis())
    val learned = journal.learned.any { it.recipeId == recipeId }
    var confirmation by remember(recipeId) { mutableStateOf<LearningConfirmation?>(null) }
    var error by remember(recipeId) { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                enabled = !cookedToday && !store.loadFailed,
                onClick = {
                    runCatching { store.recordCooked(recipeId, name) }
                        .onFailure { error = "记录未保存，请重试。" }
                },
            ) { Text(if (cookedToday) "今天已做过" else "记为做过") }
            TextButton(
                enabled = !learned && !store.loadFailed,
                onClick = {
                    runCatching { store.confirmLearned(recipeId, name) }
                        .onSuccess { confirmation = it }
                        .onFailure { error = "进度未保存，请重试。" }
                },
            ) { Text(if (learned) "已学会" else "我学会了") }
            TextButton(onClick = onOpenJournal) { Text("做菜足迹") }
        }
        if (store.loadFailed) Text("做菜足迹暂时无法读取，原始记录已保留。")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    confirmation?.let { receipt ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text("学会了$name！") },
            text = { Text("你已学会 ${journal.learned.size} 道菜。") },
            confirmButton = { Button(onClick = { confirmation = null }) { Text("完成") } },
            dismissButton = {
                TextButton(onClick = {
                    runCatching { store.undoConfirmation(receipt) }
                        .onSuccess { confirmation = null }
                        .onFailure { error = "撤销失败，请重试。" }
                }) { Text("撤销") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CookingJournalScreen(
    store: CookingJournalStore,
    recipeId: JournalRecipeId?,
    onBack: () -> Unit,
    onOpenRecipe: (JournalRecipeId) -> Unit,
) {
    val journal by store.state.collectAsState()
    var learnedOnly by rememberSaveable { mutableStateOf(false) }
    var showcaseId by rememberSaveable { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<(() -> Unit)?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val entries = remember(journal, learnedOnly, recipeId) {
        journal.entries(learnedOnly).filter { recipeId == null || it.record.recipeId == recipeId }
    }
    val dateFormat = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("做菜足迹") }, navigationIcon = { TextButton(onClick = onBack) { Text("返回") } })
    }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !learnedOnly, onClick = { learnedOnly = false }, label = { Text("全部足迹") })
                    FilterChip(selected = learnedOnly, onClick = { learnedOnly = true }, label = { Text("我的拿手菜") })
                }
            }
            if (store.loadFailed) {
                item { Text("做菜足迹暂时无法读取，原始记录已保留。") }
            } else if (entries.isEmpty()) {
                item { Text(if (learnedOnly) "还没有标记学会的菜。" else "还没有做菜足迹。") }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            items(entries, key = { it.record.id }) { entry ->
                val record = entry.record
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val catalogId = record.recipeId as? JournalRecipeId.Catalog
                        if (learnedOnly && catalogId != null) LearnedDishImage(catalogId.id)
                        Text(record.recipeName, style = MaterialTheme.typography.titleMedium)
                        val status = if (entry.learned) "已学会" else "做过"
                        Text("$status · ${dateFormat.format(Instant.ofEpochMilli(record.dateMs))}")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (recipeId == null) {
                                TextButton(onClick = { onOpenRecipe(record.recipeId) }) { Text("查看菜谱") }
                            }
                            entry.cookedId?.let { cookedId ->
                                TextButton(onClick = { removal = { store.removeCooked(cookedId) } }) { Text("删除做过记录") }
                            }
                            if (entry.learned) {
                                if (learnedOnly) {
                                    TextButton(onClick = { showcaseId = record.id }) { Text("查看 3D") }
                                }
                                TextButton(onClick = { removal = { store.removeLearned(record.recipeId) } }) {
                                    Text("取消学会标记")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    journal.learned.firstOrNull { it.id == showcaseId }?.let { learned ->
        LearnedDishShowcase(learned, onClose = { showcaseId = null })
    }

    removal?.let { remove ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text("移除这项做菜足迹？") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching(remove).onFailure { error = "足迹未移除，请重试。" }
                    removal = null
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun LearnedDishShowcase(record: CookingRecord, onClose: () -> Unit) {
    val catalogId = (record.recipeId as? JournalRecipeId.Catalog)?.id
    val assets = LocalContext.current.assets
    val model by produceState<String?>(initialValue = null, catalogId, assets) {
        value = withContext(Dispatchers.IO) {
            val name = "$catalogId.glb"
            val installed = runCatching { assets.list("cook/showcase").orEmpty().contains(name) }.getOrDefault(false)
            if (installed) "cook/showcase/$name" else "cook/scenes/showcase-plate.glb"
        }
    }
    var reset by rememberSaveable { mutableIntStateOf(0) }
    var turns by rememberSaveable { mutableIntStateOf(0) }
    val learnedDate = remember(record.dateMs) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(record.dateMs))
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(record.recipeName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val target = model
                if (target == null) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else {
                    CookSceneViewport(
                        asset = target,
                        description = "${record.recipeName} 3D 展示",
                        allowRotation = true,
                        resetCamera = reset,
                        turnSteps = turns,
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                    )
                }
                Text("首次学会 $learnedDate", style = MaterialTheme.typography.bodySmall)
                Text("拖动旋转，双指缩放。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { turns-- }, enabled = target != null) { Text("左转") }
                    TextButton(onClick = { turns++ }, enabled = target != null) { Text("右转") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = {
                turns = 0
                reset++
            }) { Text("重置视角") }
        },
    )
}

@Composable
private fun LearnedDishImage(id: Int) {
    val assets = LocalContext.current.assets
    val artwork by produceState<ImageBitmap?>(initialValue = null, id, assets) {
        value = withContext(Dispatchers.IO) {
            try {
                assets.open("cook/showcase/$id.webp").use { stream ->
                    BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                        ?.asImageBitmap()
                }
            } catch (_: IOException) {
                null
            }
        }
    }
    artwork?.let {
        Image(it, contentDescription = null, modifier = Modifier.fillMaxWidth().heightIn(max = 190.dp))
    }
}
