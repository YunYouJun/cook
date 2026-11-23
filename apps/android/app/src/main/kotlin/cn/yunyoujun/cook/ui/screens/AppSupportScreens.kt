package cn.yunyoujun.cook.ui.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.data.remote.AppFeedbackCategory
import cn.yunyoujun.cook.data.remote.FeedbackClient
import cn.yunyoujun.cook.data.remote.RecipeContributionDraft
import cn.yunyoujun.cook.platform.installedThroughGooglePlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

private const val SUPPORT_HOME = "https://support.yunle.fun/"
private const val COOK_REQUESTS = "${SUPPORT_HOME}feedback?product=cook"
private const val PRIVATE_FEEDBACK = "${SUPPORT_HOME}contact?product=cook&source=cook-android&intent=feedback"

internal enum class AppFeedbackPurpose(val title: String, val submitLabel: String) {
    FEEDBACK("意见反馈", "发送反馈"),
    CONTRIBUTION("菜谱投稿", "提交菜谱"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HelpAndFeedbackScreen(
    weChatSupport: String?,
    onOpenFeedback: () -> Unit,
    onOpenContribution: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val hasPlayListing = remember(context) { context.installedThroughGooglePlay() == true }
    Scaffold(
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("帮助与反馈") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingsNavRow("意见反馈", "反馈使用问题与功能建议", onOpenFeedback)
                    HorizontalDivider()
                    SettingsNavRow("菜谱投稿", "分享好菜和做法链接", onOpenContribution)
                }
            }
            Card(Modifier.widthIn(max = 640.dp).fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    weChatSupport?.let { url ->
                        SettingsNavRow("微信客服", "通过微信联系云乐坊客服") { context.openExternalLink(url) }
                        HorizontalDivider()
                    }
                    SettingsNavRow("支持中心", "查看帮助、在线反馈与工单") { context.openExternalLink(SUPPORT_HOME) }
                    HorizontalDivider()
                    SettingsNavRow("需求墙", "查看建议、投票与进度") { context.openExternalLink(COOK_REQUESTS) }
                    if (hasPlayListing) {
                        HorizontalDivider()
                        SettingsNavRow("Google Play 评价", "分享你使用云食册的体验") {
                            context.openExternalLink("https://play.google.com/store/apps/details?id=${context.packageName}")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun AppFeedbackScreen(
    purpose: AppFeedbackPurpose,
    client: FeedbackClient,
    recipeVersion: String,
    onOpenPrivacy: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val isContribution = purpose == AppFeedbackPurpose.CONTRIBUTION
    var category by rememberSaveable { mutableStateOf(AppFeedbackCategory.BUG) }
    var note by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    var details by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var failure by rememberSaveable { mutableStateOf(false) }
    var sent by rememberSaveable { mutableStateOf(false) }
    var receiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var ticketUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var submissionFingerprint by rememberSaveable { mutableStateOf<String?>(null) }
    var submissionId by rememberSaveable { mutableStateOf<String?>(null) }

    val draft = remember(name, source, details) { RecipeContributionDraft(name, source, details) }
    val submittedCategory = if (isContribution) AppFeedbackCategory.RECIPE else category
    val submittedNote = if (isContribution) draft.note else note.trim()
    val cleanContact = contact.trim()
    val mailTopic = if (isContribution) purpose.title else category.label
    val mailLines = listOfNotNull(submittedNote, cleanContact.takeIf(String::isNotEmpty)?.let { "联系方式：$it" })
    val editable = !sending && !sent
    val valid = if (isContribution) draft.canSubmit else submittedNote.isNotEmpty()
    val canSend = editable && valid && (client.isConfigured || !client.isTest)
    val submitLabel = if (!client.isConfigured && !client.isTest) "打开邮件草稿" else purpose.submitLabel

    fun copyMailDraft() {
        context.copyFeedbackMailDraft(mailTopic, mailLines)
    }

    fun openMailDraft() {
        context.openFeedbackMailDraft(mailTopic, mailLines)
    }

    fun send() {
        if (!canSend || sending || sent) return
        focus.clearFocus()
        if (!client.isConfigured) {
            openMailDraft()
            return
        }
        val fingerprint = feedbackFingerprint(submittedCategory, submittedNote, cleanContact)
        val id = submissionId?.takeIf { submissionFingerprint == fingerprint } ?: UUID.randomUUID().toString()
        submissionFingerprint = fingerprint
        submissionId = id
        failure = false
        sending = true
        scope.launch {
            try {
                val receipt = client.sendAppFeedback(submittedCategory, submittedNote, cleanContact, recipeVersion, id)
                receiptId = receipt?.id
                ticketUrl = receipt?.ticketUrl?.takeUnless { client.isTest }
                sent = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = true
            } finally {
                sending = false
            }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(purpose.title) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("feedback.form"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (client.isTest) {
                    Text("测试反馈仅进入独立队列。", style = MaterialTheme.typography.bodyMedium)
                    if (!client.isConfigured) {
                        Text("当前构建未配置测试反馈地址。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (sent) {
                    Text(if (client.isTest) "测试反馈已收到" else if (isContribution) "投稿已收到" else "反馈已收到")
                    receiptId?.let { Text("反馈编号：$it") }
                    ticketUrl?.let { url ->
                        OutlinedButton(onClick = { context.openExternalLink(url) }) { Text("查看私密工单与回复") }
                        TextButton(onClick = { context.shareTicket(url) }) { Text("保存私密工单链接") }
                        Text("请妥善保存链接，工单页可补充信息和查看回复。", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (isContribution) {
                    ContributionFields(
                        draft = draft,
                        enabled = editable,
                        onNameChanged = { name = it },
                        onSourceChanged = { source = it },
                        onDetailsChanged = { details = it },
                    )
                } else {
                    Text("反馈类型", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppFeedbackCategory.entries.forEach { option ->
                            FilterChip(
                                selected = category == option,
                                enabled = editable,
                                onClick = { category = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(FeedbackClient.MAX_FEEDBACK_NOTE) },
                        enabled = editable,
                        label = { Text("具体说明") },
                        placeholder = { Text(category.placeholder) },
                        supportingText = { Text("${note.length}/${FeedbackClient.MAX_FEEDBACK_NOTE}") },
                        minLines = 3,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth().testTag("feedback.note"),
                    )
                }
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it.take(FeedbackClient.MAX_CONTACT) },
                    enabled = editable,
                    label = { Text("邮箱或微信（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("feedback.contact"),
                )
                if (!client.isTest) {
                    Text(
                        if (isContribution) "投稿私密发送至云乐坊支持中心，由团队审核后决定是否收录。"
                        else "反馈私密发送至云乐坊支持中心。你可以留下联系方式以便回复。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Button(onClick = ::send, enabled = canSend, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sending) "正在提交…" else submitLabel)
                }
                if (!client.isTest && !client.isConfigured) {
                    Text("可在邮件应用中确认发送草稿，或前往支持中心。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { context.openExternalLink(PRIVATE_FEEDBACK) }) { Text("去支持中心") }
                }
                TextButton(onClick = onOpenPrivacy) { Text("隐私政策") }
            }
        }
    }

    if (failure) {
        AlertDialog(
            onDismissRequest = { failure = false },
            title = { Text("暂时没能提交") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (client.isTest) "测试内容已保留，可以稍后重试。" else "内容已保留，可以稍后重试。")
                    if (!client.isTest) {
                        TextButton(onClick = {
                            failure = false
                            context.openExternalLink(PRIVATE_FEEDBACK)
                        }) { Text("去支持中心") }
                        TextButton(onClick = {
                            failure = false
                            openMailDraft()
                        }) { Text("打开邮件草稿") }
                        TextButton(onClick = {
                            failure = false
                            copyMailDraft()
                        }) { Text("复制邮箱和内容") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { failure = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ContributionFields(
    draft: RecipeContributionDraft,
    enabled: Boolean,
    onNameChanged: (String) -> Unit,
    onSourceChanged: (String) -> Unit,
    onDetailsChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = draft.name,
        onValueChange = onNameChanged,
        enabled = enabled,
        label = { Text("菜名（必填）") },
        placeholder = { Text("例如番茄炒蛋") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("contribution.name"),
    )
    val badLink = draft.source.isNotBlank() && !draft.hasValidSource
    OutlinedTextField(
        value = draft.source,
        onValueChange = onSourceChanged,
        enabled = enabled,
        label = { Text("做法链接（必填）") },
        supportingText = {
            Text(if (badLink) "请填写以 https:// 或 http:// 开头的完整网页链接。" else "粘贴一条视频或图文链接。")
        },
        isError = badLink,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth().testTag("contribution.source"),
    )
    val remaining = FeedbackClient.MAX_FEEDBACK_NOTE - draft.note.length
    OutlinedTextField(
        value = draft.details,
        onValueChange = onDetailsChanged,
        enabled = enabled,
        label = { Text("补充说明（可选）") },
        placeholder = { Text("推荐理由、食材或做法提示") },
        supportingText = { Text(if (remaining >= 0) "还可填写 $remaining 字" else "内容超出 ${-remaining} 字，请精简后提交。") },
        isError = remaining < 0,
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().testTag("contribution.details"),
    )
}

private fun feedbackFingerprint(category: AppFeedbackCategory, note: String, contact: String): String {
    val fields = "${category.rawValue}:${note.length}:$note$contact"
    return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(fields.toByteArray()))
}

internal fun Context.openExternalLink(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        .onFailure { Toast.makeText(this, "未找到可用的应用", Toast.LENGTH_SHORT).show() }
}

internal fun Context.openFeedbackMailDraft(topic: String, lines: List<String>) {
    val intent = Intent(Intent.ACTION_SENDTO, FeedbackClient.buildMailToUri(topic, lines))
    runCatching { startActivity(intent) }.onFailure { copyFeedbackMailDraft(topic, lines) }
}

internal fun Context.copyFeedbackMailDraft(topic: String, lines: List<String>) {
    val text = FeedbackClient.buildMailPreviewText(topic, lines)
    val clipboard = getSystemService(ClipboardManager::class.java)
    val clip = ClipData.newPlainText("云食册反馈", text)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    clipboard.setPrimaryClip(clip)
    Toast.makeText(this, "已复制邮箱和内容", Toast.LENGTH_SHORT).show()
}

private fun Context.shareTicket(url: String) {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    runCatching { startActivity(Intent.createChooser(intent, "保存私密工单链接")) }
        .onFailure { Toast.makeText(this, "未找到可用的分享应用", Toast.LENGTH_SHORT).show() }
}
