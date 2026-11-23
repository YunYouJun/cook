package cn.yunyoujun.cook.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.core.ai.StepGuidanceAnswerStatus
import cn.yunyoujun.cook.core.ai.StepGuidanceDraft
import cn.yunyoujun.cook.core.ai.StepGuidanceException
import cn.yunyoujun.cook.core.ai.StepGuidanceKind
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.ai.StepGuidanceRequest
import cn.yunyoujun.cook.core.model.Recipe
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.AndroidKeystoreVault
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.ui.state.KitchenStepGuidanceModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun KitchenStepGuidance(
    container: AppContainer,
    model: KitchenStepGuidanceModel,
    recipe: Recipe,
    recipeText: RecipeText,
    dataVersion: String,
    step: RecipePresentation.Step,
    stepNumber: Int,
    stepCompleted: Boolean,
    servings: Int,
    provider: AiProviderMode,
    onOpenAiSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMembership: () -> Unit,
) {
    val sessions = container.yunlefunSessionManager
    val accountState by sessions.state.collectAsStateWithLifecycle()
    val session = accountState.membershipSession
    val revision = accountState.revision
    val context = KitchenStepGuidanceModel.Context(
        recipe.id, recipeText, dataVersion, step.id, stepCompleted, servings, provider, revision,
    )
    LaunchedEffect(model, context) { model.bind(context) }
    if (model.context != context) return

    fun assertCurrent() {
        val workbench = container.workbenchStore.entry(recipe.id)
        if (sessions.state.value.revision != revision ||
            (session != null && !sessions.isCurrentMembershipSession(session)) ||
            container.preferencesStore.state.value.aiProviderMode != provider ||
            workbench.servings != servings || (step.id in workbench.checkedSteps) != stepCompleted
        ) {
            throw CancellationException("Cooking guidance context changed")
        }
    }

    key(context) {
        KitchenStepGuidanceDialog(
            model = model,
            stepNumber = stepNumber,
            stepText = step.text,
            provider = provider,
            signedIn = session != null,
            makePrompt = { kind, question ->
                StepGuidancePrompt(
                    recipe = recipe,
                    text = recipeText,
                    dataVersion = dataVersion,
                    step = step,
                    servings = servings,
                    kind = kind,
                    question = question,
                    contextRevision = model.contextRevision,
                )
            },
            perform = { prompt ->
                assertCurrent()
                val draft = when (provider) {
                    AiProviderMode.DISABLED -> throw StepGuidanceException.NotConfigured()
                    AiProviderMode.BYOK_DEEPSEEK -> {
                        val apiKey = withContext(Dispatchers.IO) {
                            container.vault.readSecret(AndroidKeystoreVault.KEY_DEEPSEEK_API_KEY)
                        } ?: throw StepGuidanceException.NotConfigured()
                        assertCurrent()
                        container.deepSeekClient.requestStepGuidance(apiKey, prompt)
                    }
                    AiProviderMode.MANAGED_MEMBERSHIP -> {
                        val identity = session ?: throw StepGuidanceException.LoginRequired()
                        val response = try {
                            sessions.withMembershipSession(identity) { credential ->
                                container.managedStepGuidanceService.complete(prompt, credential, ::assertCurrent)
                            }
                        } catch (_: YunlefunAccountException.ExpiredSession) {
                            throw StepGuidanceException.LoginRequired()
                        }
                        StepGuidanceDraft(response.status, response.answer, response.sourceIDs)
                    }
                }
                assertCurrent()
                draft
            },
            onOpenAiSettings = onOpenAiSettings,
            onOpenAccount = onOpenAccount,
            onOpenMembership = onOpenMembership,
        )
    }
}

@Composable
internal fun KitchenStepGuidanceDialog(
    model: KitchenStepGuidanceModel,
    stepNumber: Int,
    stepText: String,
    provider: AiProviderMode,
    signedIn: Boolean,
    makePrompt: (StepGuidanceKind, String?) -> StepGuidancePrompt,
    perform: suspend (StepGuidancePrompt) -> StepGuidanceDraft,
    onOpenAiSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenMembership: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val loading = model.loading
    val asking = model.asking
    val question = model.question
    val prerequisite = when {
        provider == AiProviderMode.DISABLED -> StepGuidanceException.NotConfigured()
        provider == AiProviderMode.MANAGED_MEMBERSHIP && !signedIn -> StepGuidanceException.LoginRequired()
        else -> null
    }
    val problem = prerequisite ?: model.failure

    fun submit(kind: StepGuidanceKind, question: String?) {
        if (prerequisite != null || loading) return
        focus.clearFocus()
        model.submit(kind, question, makePrompt, perform)
    }

    AlertDialog(
        onDismissRequest = model::close,
        title = { Text("第 $stepNumber 步 · 分步指导") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stepText, style = MaterialTheme.typography.bodyMedium)
                if (prerequisite == null) {
                    Text(provider.title, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !loading, onClick = { model.asking = !asking }) {
                        Text(if (asking) "返回讲解" else "问这一步")
                    }
                }
                if (asking) {
                    OutlinedTextField(
                        value = question,
                        onValueChange = { model.question = it.take(StepGuidanceRequest.MAXIMUM_QUESTION_LENGTH) },
                        enabled = !loading,
                        label = { Text("询问当前步骤") },
                        supportingText = { Text("${question.length} / ${StepGuidanceRequest.MAXIMUM_QUESTION_LENGTH}") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (loading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在核对当前步骤原文…", style = MaterialTheme.typography.bodySmall)
                }
                problem?.let { error ->
                    Text(error.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                    when (error) {
                        is StepGuidanceException.LoginRequired -> TextButton(onClick = {
                            model.close()
                            onOpenAccount()
                        }) { Text("登录云乐坊") }
                        is StepGuidanceException.MembershipRequired -> TextButton(onClick = {
                            model.close()
                            onOpenMembership()
                        }) { Text("查看会员权益") }
                        is StepGuidanceException.NotConfigured, is StepGuidanceException.Authentication -> TextButton(onClick = {
                            model.close()
                            onOpenAiSettings()
                        }) { Text("AI 设置") }
                        else -> model.lastRequest?.let { request ->
                            TextButton(onClick = { submit(request.kind, request.question) }) { Text("重试") }
                        }
                    }
                }
                model.answer?.let { result ->
                    Text(
                        if (result.status == StepGuidanceAnswerStatus.SOURCE_DOES_NOT_SAY) "原方未说明" else "本步讲解",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(result.text)
                    TextButton(onClick = { model.showingSources = !model.showingSources }) {
                        Text(if (model.showingSources) "收起原文" else "核对原文（${result.sources.size} 处）")
                    }
                    if (model.showingSources) {
                        result.sources.forEach { cite -> Text(cite, style = MaterialTheme.typography.bodySmall) }
                    }
                    Text("用量、时间、温度和进度以菜谱原文及工作台为准。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            if (loading) {
                TextButton(onClick = model::cancelRequest) { Text("取消生成") }
            } else if (prerequisite == null) {
                Button(
                    enabled = !asking || question.isNotBlank(),
                    onClick = {
                        if (asking) submit(StepGuidanceKind.QUESTION, question)
                        else submit(StepGuidanceKind.EXPLAIN, null)
                    },
                ) { Text(if (asking) "发送问题" else "讲解这一步") }
            }
        },
        dismissButton = { TextButton(onClick = model::close) { Text("关闭") } },
    )
}
