package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.local.CookingJournalStore
import cn.yunyoujun.cook.data.local.JournalRecipeId
import cn.yunyoujun.cook.data.local.LearningConfirmation
import cn.yunyoujun.cook.ui.state.rememberLocalCalendarClock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal const val ALREADY_COOKED_TODAY = "今天已记过做过这道啦"
private const val SAVE_RECORD_FAILED = "足迹未保存，请重试。"
private val learningConfirmationSaver = Saver<LearningConfirmation?, String>(
    save = { it?.let { confirmation -> Json.encodeToString(confirmation) } },
    restore = { Json.decodeFromString<LearningConfirmation>(it) },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CookingRecordActions(
    store: CookingJournalStore,
    recipeId: JournalRecipeId,
    name: String,
    onOpenJournal: () -> Unit,
) {
    val journal by store.state.collectAsState()
    val now = rememberLocalCalendarClock()
    val cooked = remember(journal.cooked, recipeId) {
        journal.cooked.filter { it.recipeId == recipeId }.sortedByDescending { it.dateMs }
    }
    val todayRecord = cooked.firstOrNull { Instant.ofEpochMilli(it.dateMs).atZone(now.zone).toLocalDate() == now.toLocalDate() }
    val learned = journal.learned.firstOrNull { it.recipeId == recipeId }
    val editable = !store.loadFailed
    var menuExpanded by remember(recipeId) { mutableStateOf(false) }
    var confirmation by rememberSaveable(recipeId, stateSaver = learningConfirmationSaver) {
        mutableStateOf<LearningConfirmation?>(null)
    }
    var notice by rememberSaveable(recipeId) { mutableStateOf<String?>(null) }
    var error by rememberSaveable(recipeId) { mutableStateOf<String?>(null) }

    fun perform(action: () -> Unit) {
        menuExpanded = false
        notice = null
        error = null
        runCatching(action).onFailure { error = SAVE_RECORD_FAILED }
    }

    fun recordCooked() = perform {
        if (!store.recordCooked(recipeId, name)) notice = ALREADY_COOKED_TODAY
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                learned != null -> {
                    val since = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                        .format(Instant.ofEpochMilli(learned.dateMs).atZone(now.zone))
                    Box {
                        TextButton(onClick = { menuExpanded = true }) { Text("已学会 · $since") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(text = { Text("在做菜足迹中查看") }, onClick = {
                                menuExpanded = false
                                onOpenJournal()
                            })
                            DropdownMenuItem(
                                text = { Text("撤销学会标记") },
                                enabled = editable,
                                onClick = { perform { store.removeLearned(learned.id) } },
                            )
                        }
                    }
                    if (todayRecord == null) {
                        TextButton(onClick = ::recordCooked, enabled = editable) { Text("今天又做了一次") }
                    }
                }
                cooked.isNotEmpty() -> {
                    Box {
                        TextButton(onClick = { menuExpanded = true }) { Text("已做过（${cooked.size} 次）") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(if (todayRecord == null) "今天又做了一次" else "我做过这道") },
                                enabled = editable,
                                onClick = ::recordCooked,
                            )
                            DropdownMenuItem(
                                text = { Text(if (todayRecord == null) "撤销上次做过记录" else "撤销今天的做过记录") },
                                enabled = editable,
                                onClick = { perform { store.removeCooked((todayRecord ?: cooked.first()).id) } },
                            )
                            DropdownMenuItem(text = { Text("在做菜足迹中查看") }, onClick = {
                                menuExpanded = false
                                onOpenJournal()
                            })
                        }
                    }
                    OutlinedButton(
                        enabled = editable,
                        onClick = { perform { confirmation = store.confirmLearned(recipeId, name) } },
                    ) { Text("记为学会") }
                }
                else -> TextButton(enabled = editable, onClick = ::recordCooked) { Text("我做过这道") }
            }
        }
        if (store.loadFailed) Text("做菜足迹暂时无法读取，原始记录已保留。")
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    confirmation?.let { receipt ->
        CookingBadgeDialog(store, receipt, journal.learned.size) { confirmation = null }
    }
}

@Composable
internal fun KitchenCompletionDialog(
    store: CookingJournalStore,
    recipeId: JournalRecipeId.Catalog,
    name: String,
    onDismiss: () -> Unit,
    onCooked: (Boolean) -> Unit,
    onOpenJournal: () -> Unit,
) {
    val journal by store.state.collectAsState()
    val now = rememberLocalCalendarClock()
    val cookedToday = journal.cookedOn(recipeId, now.toInstant().toEpochMilli(), now.zone)
    val learned = journal.learned.any { it.recipeId == recipeId }
    var confirmation by rememberSaveable(recipeId, stateSaver = learningConfirmationSaver) {
        mutableStateOf<LearningConfirmation?>(null)
    }
    var error by rememberSaveable(recipeId) { mutableStateOf<String?>(null) }
    val receipt = confirmation
    if (receipt != null) {
        CookingBadgeDialog(store, receipt, journal.learned.size, onDismiss)
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (learned) "又做了一次" else "料理完成了吗？") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    if (!learned) {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !store.loadFailed,
                            onClick = {
                                error = null
                                runCatching { store.confirmLearned(recipeId, name) }
                                    .onSuccess { confirmation = it }
                                    .onFailure { error = SAVE_RECORD_FAILED }
                            },
                        ) { Text("今天学会了") }
                    }
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !store.loadFailed,
                        onClick = {
                            runCatching { store.recordCooked(recipeId, name) }
                                .onSuccess(onCooked)
                                .onFailure { error = SAVE_RECORD_FAILED }
                        },
                    ) {
                        Text(when {
                            cookedToday -> "今天已记录"
                            learned -> "记为做过"
                            else -> "只记为做过"
                        })
                    }
                    if (learned || journal.cooked.any { it.recipeId == recipeId }) {
                        TextButton(onClick = onOpenJournal) { Text("在做菜足迹中查看") }
                    }
                    if (store.loadFailed) Text("做菜足迹暂时无法读取，原始记录已保留。")
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("跳过") } },
        )
    }
}

@Composable
private fun CookingBadgeDialog(
    store: CookingJournalStore,
    receipt: LearningConfirmation,
    learnedCount: Int,
    onClose: () -> Unit,
) {
    val record = receipt.record
    var undoFailed by rememberSaveable(record.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("获得菜肴徽章") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LearnedDishArtwork((record.recipeId as? JournalRecipeId.Catalog)?.id, record.recipeName)
                Text("学会了${record.recipeName}", style = MaterialTheme.typography.titleMedium)
                Text("你已学会 $learnedCount 道菜。")
                if (undoFailed) Text("撤销失败，请重试。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("完成") } },
        dismissButton = {
            TextButton(onClick = {
                runCatching { store.undoConfirmation(receipt) }
                    .onSuccess { onClose() }
                    .onFailure { undoFailed = true }
            }) { Text("撤销本次确认") }
        },
    )
}
