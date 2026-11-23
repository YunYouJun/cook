package cn.yunyoujun.cook

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.core.ai.StepGuidanceAnswerStatus
import cn.yunyoujun.cook.core.ai.StepGuidanceDraft
import cn.yunyoujun.cook.core.ai.StepGuidanceException
import cn.yunyoujun.cook.core.ai.StepGuidanceKind
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.data.CookData
import cn.yunyoujun.cook.core.recipe.RecipePresentation
import cn.yunyoujun.cook.core.recipe.RecipeTextLibrary
import cn.yunyoujun.cook.data.local.AiProviderMode
import cn.yunyoujun.cook.data.local.KitchenClockReading
import cn.yunyoujun.cook.data.local.RecipeTimerState
import cn.yunyoujun.cook.data.repository.CatalogLoadState
import cn.yunyoujun.cook.ui.screens.KitchenStepGuidanceDialog
import cn.yunyoujun.cook.ui.screens.KitchenWorkbenchScreen
import cn.yunyoujun.cook.ui.state.KitchenStepGuidanceModel
import cn.yunyoujun.cook.ui.theme.CookTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KitchenStepGuidanceDialogTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Call(
        val prompt: StepGuidancePrompt,
        val result: CompletableDeferred<StepGuidanceDraft> = CompletableDeferred(),
    )

    @Test
    fun revealingTheFinishedTimerKeepsTheUnsentGuidanceQuestionAcrossRecreation() {
        val container = (compose.activity.application as CookApplication).container
        runBlocking { container.catalogRepository.loadIfNeeded() }
        val snapshot = (container.catalogRepository.state.value as CatalogLoadState.Ready).snapshot
        val recipe = requireNotNull(snapshot.recipe(250))
        val text = requireNotNull(snapshot.recipeText(recipe.id))
        val step = RecipePresentation(text).steps.first()
        val model = ViewModelProvider(compose.activity)[KitchenStepGuidanceModel::class.java]
        val showTimer = mutableStateOf(false)
        val question = "怎样判断油温？"
        compose.runOnUiThread {
            val provider = AiProviderMode.BYOK_DEEPSEEK
            container.preferencesStore.setAiProviderMode(provider)
            val now = System.currentTimeMillis()
            val timer = RecipeTimerState(durationSeconds = 60).start(KitchenClockReading(now - 65_000), recipe.name)
                .pause(KitchenClockReading(now))
            container.workbenchStore.updateTimer(recipe.id, timer)
            model.open(step.id)
            model.bind(KitchenStepGuidanceModel.Context(
                recipe.id, text, snapshot.data.meta.dataVersion, step.id, false, 1, provider,
                container.yunlefunSessionManager.state.value.revision,
            ))
            model.asking = true
            model.question = question
        }
        val content: @Composable () -> Unit = {
            CookTheme {
                KitchenWorkbenchScreen(
                    recipeId = recipe.id,
                    snapshot = snapshot,
                    container = container,
                    onBack = {},
                    revealTimer = showTimer.value,
                    onTimerRevealed = { showTimer.value = false },
                )
            }
        }
        compose.setContent(content)
        compose.onNodeWithText(question).assertIsDisplayed()
        compose.runOnIdle { showTimer.value = true }
        compose.onNodeWithText("返回分步指导").assertIsDisplayed()

        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        compose.onNodeWithText("返回分步指导").performClick()
        compose.onNodeWithText(question).assertIsDisplayed()
        compose.onNodeWithText("发送问题").assertIsEnabled()
        assertEquals(step.id, model.stepId)
    }

    @Test
    fun rotationPreservesTheQuestionRequestAndSourcesAndCancellationIgnoresLateAnswers() {
        val dataDir = File("../../../packages/data").canonicalFile
        val catalog = CookData.loadFromDirectory(dataDir)
        val recipe = catalog.recipes.first { it.id == 680 }
        val text = requireNotNull(RecipeTextLibrary.loadFromDirectory(dataDir)?.text(recipe.id))
        val step = RecipePresentation(text).steps.first()
        val workbench = KitchenStepGuidanceModel.Context(
            recipe.id, text, catalog.meta.dataVersion, step.id, false, 1, AiProviderMode.BYOK_DEEPSEEK, 0,
        )
        val model = ViewModelProvider(compose.activity)[KitchenStepGuidanceModel::class.java]
        compose.runOnUiThread {
            model.open(step.id)
            model.bind(workbench)
        }
        val calls = CopyOnWriteArrayList<Call>()
        fun awaitCall(index: Int): Call {
            compose.waitUntil(5_000) { calls.size > index }
            return calls[index]
        }

        val content: @Composable () -> Unit = {
            CookTheme {
                KitchenStepGuidanceDialog(
                    model = viewModel(),
                    stepNumber = 1,
                    stepText = step.text,
                    provider = AiProviderMode.BYOK_DEEPSEEK,
                    signedIn = false,
                    makePrompt = { kind, question ->
                        StepGuidancePrompt(recipe, text, catalog.meta.dataVersion, step, 1, kind, question)
                    },
                    perform = { prompt ->
                        val call = Call(prompt)
                        calls += call
                        withContext(NonCancellable) { call.result.await() }
                    },
                    onOpenAiSettings = {},
                    onOpenAccount = {},
                    onOpenMembership = {},
                )
            }
        }
        fun recreate() {
            compose.activityRule.scenario.recreate()
            compose.activityRule.scenario.onActivity { it.setContent(content = content) }
        }
        compose.setContent(content)

        compose.onNodeWithText("讲解这一步").performClick()
        val abandoned = awaitCall(0)
        assertEquals(StepGuidanceKind.EXPLAIN, abandoned.prompt.request.kind)
        compose.onNodeWithText("取消生成").performClick()
        abandoned.result.complete(
            StepGuidanceDraft(StepGuidanceAnswerStatus.ANSWER, "迟到的讲解", listOf(abandoned.prompt.currentStepSourceID)),
        )

        compose.onNodeWithText("问这一步").performClick()
        compose.onNodeWithText("询问当前步骤").performTextInput("怎样判断油温？")
        recreate()
        compose.onNodeWithText("怎样判断油温？").assertIsDisplayed()
        compose.onNodeWithText("发送问题").performClick()
        val firstQuestion = awaitCall(1)
        assertEquals(StepGuidanceKind.QUESTION, firstQuestion.prompt.request.kind)
        assertEquals("怎样判断油温？", firstQuestion.prompt.request.question)
        recreate()
        compose.onNodeWithText("取消生成").assertIsDisplayed()
        assertEquals(2, calls.size)
        firstQuestion.result.completeExceptionally(StepGuidanceException.RateLimited())
        compose.onNodeWithText("重试").performScrollTo().performClick()
        val retry = awaitCall(2)
        assertEquals(firstQuestion.prompt.request.question, retry.prompt.request.question)
        assertNotEquals(firstQuestion.prompt.request.requestID, retry.prompt.request.requestID)
        val cited = retry.prompt.sources.first { it.id.startsWith("ingredient:") }
        retry.result.complete(
            StepGuidanceDraft(
                StepGuidanceAnswerStatus.SOURCE_DOES_NOT_SAY,
                "原方没有说明油温。",
                listOf(retry.prompt.currentStepSourceID, cited.id),
            ),
        )

        compose.onNodeWithText("原方未说明").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("核对原文（2 处）").performScrollTo().performClick()
        recreate()
        compose.onNodeWithText(cited.text).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("迟到的讲解").assertDoesNotExist()
        compose.runOnIdle { model.bind(workbench.copy(servings = 2)) }
        compose.onNodeWithText("原方未说明").assertDoesNotExist()
        compose.onNodeWithText("怎样判断油温？").assertDoesNotExist()
        compose.onNodeWithText("讲解这一步").assertIsDisplayed()
    }
}
