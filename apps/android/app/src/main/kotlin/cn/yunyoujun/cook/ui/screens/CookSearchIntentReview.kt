package cn.yunyoujun.cook.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.ai.LocalSearchIntentParser
import cn.yunyoujun.cook.core.ai.SearchIntent
import cn.yunyoujun.cook.core.ai.SearchIntentException
import cn.yunyoujun.cook.core.ai.SearchIntentSource
import cn.yunyoujun.cook.core.ai.SearchIntentVocabulary
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.remote.ManagedCookAIException
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.repository.CookCatalogSnapshot
import cn.yunyoujun.cook.ui.state.CookSessionController
import cn.yunyoujun.cook.ui.state.SearchFilterConflict
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class SearchReviewDraft(
    val text: String,
    val intent: SearchIntent? = null,
    val acceptsUnresolved: Boolean = false,
) {
    val canApply: Boolean
        get() = intent != null && !intent.isEmpty && (intent.unresolved.isEmpty() || acceptsUnresolved)
}

private val reviewDraftSaver = Saver<SearchReviewDraft, String>(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString<SearchReviewDraft>(it) },
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun CookSearchIntentReview(
    initialText: String,
    snapshot: CookCatalogSnapshot,
    sessionController: CookSessionController,
    container: AppContainer,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    val preferences = container.preferencesStore
    val prefs by preferences.state.collectAsState()
    val sessions = container.yunlefunSessionManager
    val account by sessions.state.collectAsState()
    val mode = prefs.aiProviderMode
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val parser = remember(snapshot) { LocalSearchIntentParser(snapshot.data, snapshot.availableDietaryGoals) }
    var draft by rememberSaveable(initialText, stateSaver = reviewDraftSaver) {
        mutableStateOf(SearchReviewDraft(initialText, parser.parse(initialText)))
    }
    var showAiSettings by rememberSaveable { mutableStateOf(false) }
    var confirmSend by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<Job?>(null) }
    var requestRevision by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var hasKey by remember { mutableStateOf(false) }

    fun cancelRequest() {
        requestRevision++
        request?.cancel()
        request = null
    }

    LaunchedEffect(mode, account.revision) {
        cancelRequest()
        confirmSend = false
    }
    LaunchedEffect(mode, showAiSettings) {
        hasKey = mode == AiProviderMode.BYOK_DEEPSEEK && withContext(Dispatchers.IO) {
            runCatching { container.vault.readSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY).orEmpty() }
                .getOrDefault("").let(container.deepSeekClient::validKey)
        }
    }

    val remoteAvailable = when (mode) {
        AiProviderMode.DISABLED -> false
        AiProviderMode.BYOK_DEEPSEEK -> hasKey
        AiProviderMode.MANAGED_MEMBERSHIP -> account.membershipSession != null &&
            account.entitlement?.userID == account.membershipSession?.subject &&
            account.entitlement?.isActiveAt() == true && container.managedAiService.isConfigured
    }

    fun recognizeRemotely() {
        confirmSend = false
        val text = draft.text.trim()
        if (!remoteAvailable || text.isEmpty() || preferences.current.aiProviderMode != mode ||
            sessions.state.value.revision != account.revision
        ) return
        cancelRequest()
        errorMessage = null
        val revision = requestRevision
        val session = account.membershipSession
        val job = scope.launch(start = CoroutineStart.LAZY) {
            fun isCurrent() = revision == requestRevision && preferences.current.aiProviderMode == mode &&
                sessions.state.value.revision == account.revision
            try {
                val result = when (mode) {
                    AiProviderMode.BYOK_DEEPSEEK -> {
                        val key = withContext(Dispatchers.IO) {
                            container.vault.readSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY)
                        } ?: throw SearchIntentException.InvalidResponse()
                        if (!isCurrent()) return@launch
                        container.deepSeekClient.requestSearchIntent(key, text, snapshot.data, snapshot.availableDietaryGoals)
                    }
                    AiProviderMode.MANAGED_MEMBERSHIP -> sessions.withMembershipSession(requireNotNull(session)) { credential ->
                        val access = container.managedAiService.capabilities(credential)
                        if (!access.membershipIsActive) throw ManagedCookAIException.MembershipRequired()
                        if (!access.enabled) throw ManagedCookAIException.Unavailable()
                        ensureActive()
                        if (!isCurrent()) throw CancellationException()
                        container.managedAiService.parse(text, credential, snapshot.data, snapshot.availableDietaryGoals)
                    }
                    AiProviderMode.DISABLED -> return@launch
                }
                ensureActive()
                if (isCurrent()) draft = draft.copy(intent = result, acceptsUnresolved = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ensureActive()
                if (isCurrent()) errorMessage = when (e) {
                    is SearchIntentException, is ManagedCookAIException, is YunlefunAccountException -> e.message
                    else -> "智能识别暂不可用，可以继续编辑或重新识别本机条件。"
                }
            } finally {
                if (revision == requestRevision) request = null
            }
        }
        request = job
        job.start()
    }

    if (showAiSettings) {
        BackHandler { showAiSettings = false }
        AiSettingsScreen(container, onBack = { showAiSettings = false })
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("确认找菜条件") },
                navigationIcon = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                errorMessage?.let {
                    Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = request == null && draft.canApply,
                    onClick = {
                        val intent = draft.intent ?: return@Button
                        try {
                            sessionController.applySearchIntent(snapshot, intent, preferences)
                        } catch (e: SearchFilterConflict) {
                            errorMessage = e.message
                            focus.clearFocus()
                            return@Button
                        } catch (e: SearchIntentException) {
                            errorMessage = e.message
                            focus.clearFocus()
                            return@Button
                        }
                        preferences.recordSearch(draft.text)
                        onApplied()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("应用条件") }
            }
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.text,
                onValueChange = {
                    cancelRequest()
                    errorMessage = null
                    draft = SearchReviewDraft(it.take(500))
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("这次想怎么做") },
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(enabled = draft.text.isNotBlank(), onClick = {
                    focus.clearFocus()
                    cancelRequest()
                    errorMessage = null
                    draft = draft.copy(intent = parser.parse(draft.text.trim()), acceptsUnresolved = false)
                }) { Text("重新识别本地条件") }
                if (remoteAvailable) {
                    TextButton(enabled = request == null && draft.text.isNotBlank(), onClick = {
                        focus.clearFocus()
                        confirmSend = true
                    }) { Text(if (mode == AiProviderMode.MANAGED_MEMBERSHIP) "智能找菜 · 会员 DeepSeek" else "智能找菜 · DeepSeek") }
                }
                TextButton(onClick = {
                    focus.clearFocus()
                    cancelRequest()
                    showAiSettings = true
                }) { Text("AI 设置") }
            }
            if (mode == AiProviderMode.MANAGED_MEMBERSHIP && !remoteAvailable) {
                Text("会员智能识别当前不可用。", style = MaterialTheme.typography.bodySmall)
            }
            if (request != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("正在识别条件…", Modifier.weight(1f))
                    TextButton(onClick = ::cancelRequest) { Text("取消请求") }
                }
            }
            val intent = draft.intent
            if (intent == null) {
                Text("文本已修改，请重新识别条件。", style = MaterialTheme.typography.bodySmall)
            } else {
                SearchReviewConditions(
                    snapshot = snapshot,
                    intent = intent,
                    enabled = request == null,
                    onChange = {
                        errorMessage = null
                        draft = draft.copy(intent = it)
                    },
                )
                if (intent.unresolved.isNotEmpty()) {
                    Text("待确认", style = MaterialTheme.typography.titleSmall)
                    intent.unresolved.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    ReviewCheckbox("仅应用上方条件", draft.acceptsUnresolved, request == null) {
                        draft = draft.copy(acceptsUnresolved = it)
                    }
                }
            }
        }
    }

    if (confirmSend) {
        val recipient = if (mode == AiProviderMode.MANAGED_MEMBERSHIP) "云乐坊（腾讯云 CloudBase）和 DeepSeek" else "DeepSeek"
        AlertDialog(
            onDismissRequest = { confirmSend = false },
            title = { Text("发送本次搜索？") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("以下文字将发送给$recipient。")
                    Text(draft.text.trim())
                }
            },
            confirmButton = { Button(onClick = ::recognizeRemotely) { Text("发送并智能识别") } },
            dismissButton = { TextButton(onClick = { confirmSend = false }) { Text("取消发送") } },
        )
    }
}

private data class SearchConditionField(
    val label: String,
    val options: List<String>,
    val selected: List<String>,
    val single: Boolean = false,
    val onChange: (List<String>) -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchReviewConditions(
    snapshot: CookCatalogSnapshot,
    intent: SearchIntent,
    enabled: Boolean,
    onChange: (SearchIntent) -> Unit,
) {
    val vocabulary = remember(snapshot) { SearchIntentVocabulary(snapshot.data) }
    var editingField by rememberSaveable { mutableStateOf<String?>(null) }
    val q = intent.query
    val goals = snapshot.availableDietaryGoals
    val fields = listOf(
        SearchConditionField("食材", vocabulary.stuff, q.stuff) { onChange(intent.copy(query = q.copy(stuff = it))) },
        SearchConditionField("忌口", vocabulary.stuff, q.avoid) { onChange(intent.copy(query = q.copy(avoid = it))) },
        SearchConditionField("厨具", vocabulary.tools, listOfNotNull(q.tool), single = true) {
            onChange(intent.copy(query = q.copy(tool = it.firstOrNull())))
        },
        SearchConditionField("做法", vocabulary.methodGroups, q.methodGroups) { onChange(intent.copy(query = q.copy(methodGroups = it))) },
        SearchConditionField("难度", vocabulary.difficulty, q.difficulty) { onChange(intent.copy(query = q.copy(difficulty = it))) },
        SearchConditionField("饮食目标", goals.map { it.title }, listOfNotNull(intent.goal?.title), single = true) { picked ->
            onChange(intent.copy(goal = goals.firstOrNull { it.title == picked.firstOrNull() }))
        },
    )

    Text(if (intent.source == SearchIntentSource.DEEPSEEK) "DeepSeek 识别条件" else "本机识别条件", style = MaterialTheme.typography.titleSmall)
    if (intent.isEmpty) Text("可以在下方补充条件。", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fields.forEach { field ->
            field.selected.forEach { value ->
                InputChip(
                    selected = true,
                    enabled = enabled,
                    onClick = { field.onChange(field.selected - value) },
                    label = { Text(if (field.label == "忌口") "忌口 $value" else value) },
                    trailingIcon = { Text("×") },
                    modifier = Modifier.semantics { contentDescription = "移除${field.label}$value" },
                )
            }
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        fields.filter { it.options.isNotEmpty() }.forEach { field ->
            FilterChip(
                selected = field.selected.isNotEmpty(),
                enabled = enabled,
                onClick = { editingField = field.label },
                label = { Text("编辑${field.label}") },
            )
        }
    }
    ReviewCheckbox("不吃辣", q.noSpicy, enabled) { onChange(intent.copy(query = q.copy(noSpicy = it))) }

    fields.firstOrNull { it.label == editingField && enabled }?.let { field ->
        SearchConditionPicker(field, onClose = { editingField = null })
    }
}

@Composable
private fun SearchConditionPicker(field: SearchConditionField, onClose: () -> Unit) {
    var filter by rememberSaveable(field.label) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("编辑${field.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (field.options.size > 10) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        label = { Text("搜索${field.label}") },
                    )
                }
                LazyColumn(Modifier.heightIn(max = 280.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(field.options.filter { it.contains(filter.trim(), ignoreCase = true) }, key = { it }) { option ->
                        ReviewCheckbox(option, option in field.selected, enabled = true) { checked ->
                            val next = when {
                                !checked -> field.selected - option
                                field.single -> listOf(option)
                                else -> field.selected + option
                            }
                            field.onChange(next)
                            if (field.single) onClose()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("完成") } },
    )
}

@Composable
private fun ReviewCheckbox(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label)
    }
}
