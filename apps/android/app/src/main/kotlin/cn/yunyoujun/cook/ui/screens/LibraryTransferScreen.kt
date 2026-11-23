package cn.yunyoujun.cook.ui.screens

import android.content.ClipboardManager
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.platform.LIBRARY_EXPORT_FILENAME
import cn.yunyoujun.cook.ui.state.LibraryTransferViewModel
import cn.yunyoujun.cook.ui.state.LibraryTransferViewModel.DocumentAction

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryTransferScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val transfer: LibraryTransferViewModel = viewModel()
    val account by container.yunlefunSessionManager.state.collectAsState()
    val scope = account.libraryScope
    val ready = account.isHydrated && !transfer.busy
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        transfer.documentReturned(context, container, DocumentAction.SAVE, it)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        transfer.documentReturned(context, container, DocumentAction.IMPORT, it)
    }
    val activity = LocalActivity.current
    val activityLifecycle = activity as? LifecycleOwner ?: LocalLifecycleOwner.current
    LifecycleResumeEffect(transfer.outputReady, lifecycleOwner = activityLifecycle) {
        transfer.deliverOutput(activity ?: context, container)
        onPauseOrDispose { }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("导入与导出") },
            navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
        )
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("收藏与浏览历史", style = MaterialTheme.typography.headlineSmall)
            val source = when {
                !account.isHydrated -> "正在读取收藏与历史库…"
                scope.usesAccountLibrary -> "当前库：云乐坊账号（${account.account?.displayName ?: "已登录"}）"
                else -> "当前库：本机设备"
            }
            Text(source, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导入", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (scope.usesAccountLibrary) "导入会合并到当前账号并同步到其他设备。容量不足时优先保留已有记录。"
                        else "导入会合并到本机。容量不足时优先保留已有记录。",
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = ready, onClick = {
                            transfer.chooseDocument(DocumentAction.IMPORT, scope) {
                                openFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }
                        }) { Text("从文件导入") }
                        OutlinedButton(enabled = ready, onClick = {
                            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                            transfer.importClipboard(container, scope, clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
                        }) { Text("从剪贴板导入") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导出", style = MaterialTheme.typography.titleMedium)
                    Text("可将同一份互通 JSON 保存或发送到网页版、iOS 和其他 Android 设备。")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = ready, onClick = {
                            transfer.chooseDocument(DocumentAction.SAVE, scope) { saveFile.launch(LIBRARY_EXPORT_FILENAME) }
                        }) { Text("保存 JSON 文件") }
                        OutlinedButton(enabled = ready, onClick = { transfer.share(context, container, scope) }) {
                            Text("分享 JSON 文件")
                        }
                        OutlinedButton(enabled = ready, onClick = { transfer.copy(container, scope) }) {
                            Text("复制 JSON")
                        }
                    }
                }
            }

            val message = transfer.message ?: "正在处理收藏与浏览历史…".takeIf { transfer.isWorking }
            if (message != null) {
                Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(message)
                    if (!transfer.isWorking) TextButton(onClick = transfer::clearMessage) { Text("收起") }
                }
            }
        }
    }
}
