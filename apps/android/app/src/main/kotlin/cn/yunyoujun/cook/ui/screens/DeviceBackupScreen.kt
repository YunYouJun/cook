package cn.yunyoujun.cook.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.data.local.DeviceBackupSection
import cn.yunyoujun.cook.data.local.DeviceBackupStore
import cn.yunyoujun.cook.ui.state.DeviceBackupViewModel
import cn.yunyoujun.cook.ui.state.DeviceBackupViewModel.FileAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceBackupScreen(store: DeviceBackupStore, onBack: () -> Unit) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val backup = viewModel { DeviceBackupViewModel(store) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        backup.fileReturned(FileAction.EXPORT, resolver, uri)
    }
    val exportWithVideos = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        backup.fileReturned(FileAction.EXPORT_WITH_VIDEOS, resolver, uri)
    }
    val read = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        backup.fileReturned(FileAction.IMPORT, resolver, uri)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("本机备份与换机") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("返回") }
        })
    }) { inset ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inset).testTag("deviceBackup.sections"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("保存本机备份，换机后可分项恢复。")
                    Text(
                        "包含本机收藏、浏览历史、做菜偏好、冰箱、私房菜文本、个人词典和做菜足迹。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "含视频的备份会复制本机保存的私房菜视频。请把文件保存在你信任的位置。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(enabled = !backup.busy, onClick = {
                        backup.chooseFile(FileAction.EXPORT) {
                            export.launch("cook-device-backup-${LocalDate.now()}.json")
                        }
                    }) { Text("保存备份") }
                    OutlinedButton(enabled = !backup.busy, onClick = {
                        backup.chooseFile(FileAction.EXPORT_WITH_VIDEOS) {
                            exportWithVideos.launch("cook-device-backup-${LocalDate.now()}.zip")
                        }
                    }) { Text("保存含视频的备份") }
                    OutlinedButton(enabled = !backup.busy, onClick = {
                        backup.chooseFile(FileAction.IMPORT) {
                            read.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "text/plain"))
                        }
                    }) { Text("选择备份恢复") }
                    if (backup.working) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    backup.status?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (backup.hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            backup.preview?.let { preview ->
                item {
                    val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(preview.document.exportedAtMs))
                    Text("备份于 $date", style = MaterialTheme.typography.titleSmall)
                }
                items(DeviceBackupSection.entries) { section ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(section.title, style = MaterialTheme.typography.titleSmall)
                                Text(preview.detail(section), style = MaterialTheme.typography.bodySmall)
                            }
                            val restoredSection = section in backup.restored
                            TextButton(
                                enabled = !backup.busy && !restoredSection,
                                modifier = Modifier.semantics { contentDescription = "恢复${section.title}" },
                                onClick = { backup.requestRestore(section) },
                            ) { Text(if (restoredSection) "已恢复" else "恢复") }
                        }
                    }
                }
            }
        }
    }
    backup.confirming?.let { section ->
        AlertDialog(
            onDismissRequest = backup::cancelRestore,
            title = { Text("恢复${section.title}？") },
            text = {
                Text(
                    when (section) {
                        DeviceBackupSection.PREFERENCES -> "这会将备份中的做菜偏好应用到这台设备。"
                        DeviceBackupSection.PRIVATE_RECIPES -> "恢复新菜谱及其备份中的视频，本机已有或已删除的菜谱保持原样。"
                        else -> "将备份合并到这台设备并保留本机已有内容。"
                    },
                )
            },
            dismissButton = { TextButton(onClick = backup::cancelRestore) { Text("取消") } },
            confirmButton = {
                Button(onClick = { backup.confirmRestore(resolver) }) { Text("确认恢复") }
            },
        )
    }
}
