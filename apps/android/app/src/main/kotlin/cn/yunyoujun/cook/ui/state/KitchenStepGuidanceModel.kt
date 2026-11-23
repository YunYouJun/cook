package cn.yunyoujun.cook.ui.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.core.ai.StepGuidanceAnswerStatus
import cn.yunyoujun.cook.core.ai.StepGuidanceDraft
import cn.yunyoujun.cook.core.ai.StepGuidanceException
import cn.yunyoujun.cook.core.ai.StepGuidanceKind
import cn.yunyoujun.cook.core.ai.StepGuidancePrompt
import cn.yunyoujun.cook.core.ai.StepGuidanceRequest
import cn.yunyoujun.cook.core.ai.StepGuidanceValidator
import cn.yunyoujun.cook.core.recipe.RecipeText
import cn.yunyoujun.cook.data.local.AiProviderMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Keeps the current question and answer in memory for the lifetime of the workbench entry. */
internal class KitchenStepGuidanceModel : ViewModel() {
    data class Context(
        val recipeId: Int,
        val recipeText: RecipeText,
        val dataVersion: String,
        val stepId: String,
        val stepCompleted: Boolean,
        val servings: Int,
        val provider: AiProviderMode,
        val accountRevision: Long,
    )

    data class Answer(val status: StepGuidanceAnswerStatus, val text: String, val sources: List<String>)

    var stepId by mutableStateOf<String?>(null)
        private set
    var context by mutableStateOf<Context?>(null)
        private set
    var contextRevision = UUID.randomUUID().toString()
        private set
    var asking by mutableStateOf(false)
    var question by mutableStateOf("")
    var showingSources by mutableStateOf(false)
    var answer by mutableStateOf<Answer?>(null)
        private set
    var failure by mutableStateOf<StepGuidanceException?>(null)
        private set
    var lastRequest by mutableStateOf<StepGuidanceRequest?>(null)
        private set
    private var pending by mutableStateOf<StepGuidancePrompt?>(null)
    private var requestJob: Job? = null
    val loading: Boolean get() = pending != null

    fun open(stepId: String) {
        close()
        this.stepId = stepId
    }

    fun bind(current: Context) {
        if (current.stepId != stepId || context == current) return
        clearDraft()
        contextRevision = UUID.randomUUID().toString()
        context = current
    }

    fun close() {
        clearDraft()
        stepId = null
        context = null
    }

    private fun clearDraft() {
        cancelRequest()
        asking = false
        question = ""
        showingSources = false
        answer = null
        failure = null
        lastRequest = null
    }

    fun cancelRequest() {
        pending = null
        requestJob?.cancel()
        requestJob = null
    }

    fun submit(
        kind: StepGuidanceKind,
        question: String?,
        makePrompt: (StepGuidanceKind, String?) -> StepGuidancePrompt,
        perform: suspend (StepGuidancePrompt) -> StepGuidanceDraft,
    ) {
        if (loading) return
        answer = null
        failure = null
        showingSources = false
        lastRequest = null
        val prompt = try {
            makePrompt(kind, question)
        } catch (error: StepGuidanceException) {
            failure = error
            return
        }
        lastRequest = prompt.request
        pending = prompt
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val checked = StepGuidanceValidator.validate(perform(prompt), prompt)
                ensureActive()
                if (pending === prompt) {
                    answer = Answer(checked.status, checked.answer, checked.sourceIDs.mapNotNull(prompt::sourceText))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (pending === prompt) failure = error as? StepGuidanceException ?: StepGuidanceException.Unavailable()
            } finally {
                if (pending === prompt) {
                    pending = null
                    requestJob = null
                }
            }
        }
        requestJob = job
        job.start()
    }
}
