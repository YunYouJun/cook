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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.yunyoujun.cook.ui.state.FridgeBatchReview
import cn.yunyoujun.cook.ui.state.FridgeBatchReview.Phase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun FridgeVisionDialog(
    review: FridgeBatchReview,
    onBack: () -> Unit,
    onReplacePhoto: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMembership: () -> Unit,
    onOpenPrivacy: () -> Unit,
    writeItems: suspend (List<String>) -> Unit,
    onSaved: () -> Unit,
) {
    val phase = review.phase
    val saving = review.isSaving
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (review.phase is Phase.MembershipRequired) review.refreshAccess()
    }

    AlertDialog(
        onDismissRequest = { if (!review.isSaving) onBack() },
        title = {
            val hasResults = phase is Phase.Results && phase.candidates.isNotEmpty()
            Text(if (hasResults) "核对识别结果" else "批量识图")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FridgeVisionContent(
                    review, phase, onReplacePhoto, onOpenPrivacy,
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                )
                if (phase is Phase.Ready && !phase.uploading) {
                    Text(
                        "确认后，处理后的照片将发送给${phase.provider.recipient}。",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val error = when (phase) {
                    is Phase.Ready -> phase.message
                    is Phase.Results -> when {
                        phase.saveError -> "食材未保存，请重试。"
                        phase.candidates.any { it.included && !review.isValid(it.name) } ->
                            "请为每项勾选的食材填写一个有效名称，不超过 30 字。"
                        phase.candidates.isNotEmpty() && phase.candidates.none { it.included } ->
                            "请至少勾选一项要加入冰箱的食材。"
                        else -> null
                    }
                    else -> null
                }
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    })
                }
            }
        },
        confirmButton = {
            when (phase) {
                Phase.Checking -> Unit
                is Phase.LoginRequired -> Button(onClick = onOpenAccount) {
                    Text(if (phase.sessionRejected) "前往账号设置" else "登录云乐坊账号")
                }
                is Phase.MembershipRequired -> Button(onClick = onOpenMembership) { Text("查看会员权益") }
                is Phase.Unavailable -> Button(onClick = review::refreshAccess) { Text("重试") }
                is Phase.Ready -> Button(enabled = !phase.uploading, onClick = { review.recognize(phase) }) { Text("同意并识别") }
                is Phase.Results -> if (phase.candidates.isNotEmpty()) {
                    val names = review.namesToSave(phase)
                    Button(enabled = !saving && names.isNotEmpty(), onClick = { review.save(writeItems, onSaved) }) {
                        Text(if (saving) "正在保存…" else "加入冰箱（${names.size} 样）")
                    }
                }
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onBack) { Text("返回本机识别") } },
    )
}

@Composable
private fun FridgeVisionContent(
    review: FridgeBatchReview,
    phase: Phase,
    onReplacePhoto: () -> Unit,
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val saving = review.isSaving
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (phase) {
            Phase.Checking -> Text("正在核对会员资格和照片接收方…")
            is Phase.LoginRequired -> Text(
                if (phase.sessionRejected) "请在云乐坊账号设置退出并重新登录。当前照片会保留。"
                else "请登录云乐坊后使用批量识图。",
            )
            is Phase.MembershipRequired -> {
                Text("批量识图需要有效的云乐坊会员。")
                TextButton(onClick = review::refreshAccess) { Text("重新核对会员资格") }
            }
            is Phase.Unavailable -> Text(phase.message, color = MaterialTheme.colorScheme.error)
            is Phase.Ready -> {
                val preview by produceState<ImageBitmap?>(null, review) {
                    value = withContext(Dispatchers.Default) {
                        BitmapFactory.decodeByteArray(review.jpeg, 0, review.jpeg.size)?.asImageBitmap()
                    }
                }
                preview?.let {
                    Image(it, contentDescription = "将上传的照片", modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp))
                }
                if (phase.uploading) {
                    Text("正在识别照片…")
                } else {
                    Text("上传前会移除位置等信息。请避开人物、地址等私密内容。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onOpenPrivacy) { Text("隐私说明") }
                }
            }
            is Phase.Results -> {
                if (phase.candidates.isEmpty()) {
                    Text("没有识别出食材。可以换一张照片或手动添加。")
                } else {
                    Text("核对名称，取消勾选识别错的食材", style = MaterialTheme.typography.bodySmall)
                    phase.candidates.forEachIndexed { index, candidate ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = candidate.included,
                                enabled = !saving,
                                modifier = Modifier.semantics { contentDescription = "选择${candidate.name}" },
                                onCheckedChange = { review.setIncluded(index, it) },
                            )
                            OutlinedTextField(
                                value = candidate.name,
                                enabled = !saving,
                                onValueChange = { review.setName(index, it) },
                                label = { Text("食材 ${index + 1}") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                isError = candidate.included && !review.isValid(candidate.name),
                            )
                        }
                    }
                    Text("每种记一份，日期按食用建议估算，入库后可调整。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (phase !is Phase.Ready || !phase.uploading) {
            TextButton(enabled = !saving, onClick = onReplacePhoto) { Text("换一张照片") }
        }
    }
}
