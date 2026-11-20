package cn.yunyoujun.cook.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.remote.FridgeVisionException
import cn.yunyoujun.cook.data.remote.FridgeVisionProvider
import cn.yunyoujun.cook.data.remote.FridgeVisionService
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class VisionCandidate(val name: String, val included: Boolean = true)

@Composable
internal fun FridgeVisionDialog(
    jpeg: ByteArray,
    service: FridgeVisionService,
    sessions: YunlefunSessionManager,
    normalize: (String) -> String,
    onBack: () -> Unit,
    onOpenAccount: () -> Unit,
    onAdd: (List<String>) -> Unit,
) {
    val session by sessions.state.collectAsState()
    val credential = session.credential
    val scope = rememberCoroutineScope()
    var provider by remember(credential) { mutableStateOf<FridgeVisionProvider?>(null) }
    var eligibilityError by remember(credential) { mutableStateOf<Exception?>(null) }
    var message by remember(credential) { mutableStateOf<String?>(null) }
    var candidates by remember(credential) { mutableStateOf<List<VisionCandidate>?>(null) }
    var uploading by remember(credential) { mutableStateOf(false) }
    var uploadJob by remember(credential) { mutableStateOf<Job?>(null) }
    var checkNumber by remember { mutableIntStateOf(0) }
    val preview = remember(jpeg) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() }

    DisposableEffect(credential) {
        onDispose { uploadJob?.cancel() }
    }

    LaunchedEffect(credential, checkNumber) {
        provider = null
        eligibilityError = null
        try {
            provider = service.memberProvider(credential ?: throw FridgeVisionException.Login())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            eligibilityError = e
        }
    }

    val names = candidates.orEmpty().filter { it.included }.map { normalize(it.name) }.distinct()
    val namesValid = names.isNotEmpty() && names.all { FridgeVisionService.validName(it) }
    val recipient = provider
    val problem = eligibilityError

    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(if (candidates == null) "批量识图" else "核对识别结果") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val rows = candidates
                if (rows == null) {
                    preview?.let { Image(it, contentDescription = "将上传的照片", modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
                    when {
                        uploading -> Text("正在识别照片…")
                        recipient != null -> Text("确认后，处理后的照片将发送给${recipient.recipient}。")
                        problem != null -> Text(problem.message.orEmpty())
                        else -> Text("正在核对会员资格和照片接收方…")
                    }
                } else {
                    rows.forEachIndexed { index, candidate ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = candidate.included,
                                onCheckedChange = { included ->
                                    candidates = rows.toMutableList().apply { set(index, candidate.copy(included = included)) }
                                },
                            )
                            OutlinedTextField(
                                value = candidate.name,
                                onValueChange = { name ->
                                    candidates = rows.toMutableList().apply { set(index, candidate.copy(name = name.take(30))) }
                                },
                                label = { Text("食材 ${index + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                isError = candidate.included && !FridgeVisionService.validName(normalize(candidate.name)),
                            )
                        }
                    }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            when {
                candidates != null -> Button(enabled = namesValid, onClick = {
                    if (sessions.state.value.credential != credential) return@Button
                    runCatching { onAdd(names) }.onFailure { message = "食材未保存，请重试。" }
                }) { Text("加入冰箱（${names.size} 样）") }
                problem is FridgeVisionException.Login || problem is FridgeVisionException.Membership ->
                    Button(onClick = onOpenAccount) { Text("云乐坊账号") }
                problem != null -> Button(onClick = { checkNumber++ }) { Text("重试") }
                recipient != null && credential != null -> Button(enabled = !uploading, onClick = {
                    if (sessions.state.value.credential != credential) return@Button
                    uploading = true
                    message = null
                    uploadJob = scope.launch {
                        try {
                            val result = service.recognize(jpeg, recipient, credential)
                            if (sessions.state.value.credential != credential) return@launch
                            if (result.isEmpty()) {
                                message = "这张照片未识别出食材，可换一张照片。"
                            } else {
                                candidates = result.map { VisionCandidate(it) }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: FridgeVisionException.ProviderChanged) {
                            message = e.message
                            provider = null
                            checkNumber++
                        } catch (e: Exception) {
                            message = e.message ?: "识图失败，请重试。"
                        } finally {
                            uploading = false
                        }
                    }
                }) { Text("同意并识别") }
            }
        },
        dismissButton = { TextButton(onClick = onBack) { Text("返回本机识别") } },
    )
}
