package cn.yunyoujun.cook.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.data.remote.FeedbackClient
import cn.yunyoujun.cook.data.remote.FeedbackLinkIssue
import cn.yunyoujun.cook.data.remote.RecipeIssueKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun RecipeFeedbackScreen(
    recipe: Recipe?,
    client: FeedbackClient,
    recipeVersion: String,
    onBack: () -> Unit,
) {
    val submission = viewModel { RecipeReportSubmission() }
    val submitting = submission.status == ReportStatus.SUBMITTING
    BackHandler(enabled = submitting) { }
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("反馈这道菜") },
                navigationIcon = { TextButton(enabled = !submitting, onClick = onBack) { Text("返回") } },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recipe == null) {
                    Text("这道菜已不在当前菜谱库。")
                } else if (submission.status == ReportStatus.SENT) {
                    Text(
                        if (client.isTest) "测试反馈已收到" else "收到了，谢谢反馈",
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    Button(onClick = onBack) { Text("完成") }
                } else {
                    RecipeFeedbackForm(recipe, client, recipeVersion, submission)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecipeFeedbackForm(
    recipe: Recipe,
    client: FeedbackClient,
    recipeVersion: String,
    submission: RecipeReportSubmission,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val issues = RecipeIssueKind.availableFor(recipe)
    var issue by rememberSaveable(recipe.id) { mutableStateOf(issues.first()) }
    var linkIssue by rememberSaveable(recipe.id) { mutableStateOf(FeedbackLinkIssue.UNSPECIFIED) }
    var note by rememberSaveable(recipe.id) { mutableStateOf("") }
    val refersToLink = issue == RecipeIssueKind.VIDEO || issue == RecipeIssueKind.TEXT
    val editable = submission.status != ReportStatus.SUBMITTING
    val failed = submission.status == ReportStatus.FAILED
    val fallback = !client.isTest && (failed || !client.isConfigured)
    val maxNote = FeedbackClient.MAX_RECIPE_NOTE
    val video = recipe.videoUrl
    val article = recipe.link?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    fun mailLines(): List<String> = buildList {
        add("菜谱：${recipe.name}（#${recipe.id}）")
        add("问题：${issue.label}")
        if (refersToLink && linkIssue != FeedbackLinkIssue.UNSPECIFIED) add("具体表现：${linkIssue.label}")
        add(note.trim())
        video?.let { add("视频做法：$it") }
        article?.let { add("图文做法：$it") }
    }

    Text(recipe.name, style = MaterialTheme.typography.titleLarge)
    Text("菜谱 #${recipe.id} · 将随反馈一起提交")
    if (video != null || article != null) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            video?.let { url ->
                TextButton(enabled = editable, onClick = { context.openExternalLink(url) }) { Text("视频做法") }
            }
            article?.let { url ->
                TextButton(enabled = editable, onClick = { context.openExternalLink(url) }) { Text("图文做法") }
            }
        }
    }
    if (client.isTest) {
        Text(if (client.isConfigured) "测试反馈仅进入独立队列。" else "当前构建未配置测试反馈地址。")
    }
    Text("问题类型", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        issues.forEach { option ->
            FilterChip(selected = issue == option, enabled = editable, onClick = { issue = option }, label = { Text(option.label) })
        }
    }
    if (refersToLink) {
        Text("具体表现（选填）", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeedbackLinkIssue.entries.forEach { option ->
                FilterChip(
                    selected = linkIssue == option,
                    enabled = editable,
                    onClick = { linkIssue = option },
                    label = { Text(option.label) },
                )
            }
        }
    }
    OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(maxNote).trimEnd(Char::isHighSurrogate) },
        enabled = editable,
        label = { Text(if (issue == RecipeIssueKind.OTHER) "请描述具体问题" else "补充说明（选填）") },
        supportingText = { Text("${note.length} / $maxNote") },
        minLines = 3,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth().testTag("recipe-feedback.note"),
    )
    Text("无需登录。提交会附带做法链接、App 和菜谱版本。", style = MaterialTheme.typography.bodySmall)
    if (failed) {
        Text(
            "暂时没能提交，内容已保留，可以重试。",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val valid = issue != RecipeIssueKind.OTHER || note.isNotBlank()
    if (client.isConfigured) {
        Button(
            enabled = editable && valid,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                focus.clearFocus()
                submission.submit(client, recipe, issue, note, linkIssue, recipeVersion)
            },
        ) {
            Text(if (!editable) "正在提交…" else if (client.isTest) "提交测试反馈" else "提交反馈")
        }
    }
    if (fallback) {
        if (!client.isConfigured) Text("当前构建只能通过邮件反馈。", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = valid, onClick = { context.openFeedbackMailDraft(recipe.name, mailLines()) }) {
            Text("打开带菜谱信息的邮件草稿")
        }
    }
}

private enum class ReportStatus { EDITING, SUBMITTING, FAILED, SENT }

private class RecipeReportSubmission : ViewModel() {
    var status by mutableStateOf(ReportStatus.EDITING)
        private set

    fun submit(
        client: FeedbackClient,
        recipe: Recipe,
        issue: RecipeIssueKind,
        note: String,
        linkIssue: FeedbackLinkIssue,
        recipeVersion: String,
    ) {
        if (status == ReportStatus.SUBMITTING || status == ReportStatus.SENT) return
        status = ReportStatus.SUBMITTING
        viewModelScope.launch {
            try {
                client.reportRecipeIssue(recipe, issue, note, linkIssue, recipeVersion)
                status = ReportStatus.SENT
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = ReportStatus.FAILED
            }
        }
    }
}
