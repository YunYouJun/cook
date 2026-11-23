package cn.yunyoujun.cook.ui.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.data.remote.WeChatAccountException
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.WeChatAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal data class WeChatAccountActionState(
    val revision: Long? = null,
    val working: Boolean = false,
    val authorizing: Boolean = false,
    val status: String? = null,
    val failure: String? = null,
)

internal class WeChatAccountViewModel(
    private val authorization: WeChatAuthorization,
    private val sessions: YunlefunSessionManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(WeChatAccountActionState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    init {
        viewModelScope.launch {
            sessions.state.map { it.revision }.distinctUntilChanged().collect { revision ->
                val action = state.value
                if (action.authorizing && action.revision != revision) cancelAuthorization()
            }
        }
    }

    fun confirm(revision: Long) {
        val account = sessions.state.value
        if (state.value.working || account.revision != revision) return
        val binding = account.credential != null
        mutableState.value = WeChatAccountActionState(revision = revision, working = true)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val authorize: suspend () -> String? = {
                    if (sessions.state.value.revision != revision) throw CancellationException("Account changed")
                    mutableState.value = state.value.copy(authorizing = true)
                    try {
                        authorization.authorize()
                    } finally {
                        mutableState.value = state.value.copy(authorizing = false)
                    }
                }
                if (binding) {
                    if (sessions.bindWeChat(authorize)) {
                        mutableState.value = state.value.copy(status = "已将微信绑定到当前云乐坊账号。")
                    }
                } else {
                    sessions.signInWithWeChat(authorize)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = state.value.copy(failure = weChatUiMessage(error))
            } finally {
                mutableState.value = state.value.copy(working = false)
                operation = null
            }
        }
        operation = job
        job.start()
    }

    fun cancelAuthorization() {
        if (state.value.authorizing) operation?.cancel()
    }
}

internal fun weChatUiMessage(error: Exception): String =
    when (error) {
        is WeChatAccountException, is YunlefunAccountException -> error.message ?: "微信授权暂不可用，请稍后重试。"
        else -> "微信授权暂不可用，请稍后重试。"
    }
