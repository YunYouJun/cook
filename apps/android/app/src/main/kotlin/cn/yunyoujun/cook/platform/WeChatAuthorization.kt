package cn.yunyoujun.cook.platform

import android.content.Intent
import android.os.SystemClock
import cn.yunyoujun.cook.data.remote.WeChatAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.remote.isWeChatAuthorizationCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

internal const val WECHAT_PACKAGE = "com.tencent.mm"

internal class WeChatAuthorization(
    private val driver: Driver?,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    interface Driver {
        fun isInstalled(): Boolean
        fun send(state: String): Boolean
        fun handle(intent: Intent, onResponse: (ResponseStatus, String?, String?) -> Unit)
    }

    enum class ResponseStatus { AUTHORIZED, CANCELLED, FAILED }

    private class Pending(val state: String, val deadline: Long) {
        val result = CompletableDeferred<String?>()
    }

    private val pending = AtomicReference<Pending?>()
    val isAwaiting: Boolean get() = pending.get() != null

    val isEnabled: Boolean get() = driver != null

    val isAvailable: Boolean
        get() = driver?.isInstalled() == true

    suspend fun authorize(): String? = withContext(dispatcher) {
        val sdk = driver?.takeIf(Driver::isInstalled)
            ?: throw WeChatAccountException(WeChatAccountException.Reason.UNAVAILABLE)
        val request = Pending(YunlefunSSO.randomToken(), elapsedMillis() + TIMEOUT_MS)
        if (!pending.compareAndSet(null, request)) {
            throw WeChatAccountException(WeChatAccountException.Reason.UNAVAILABLE)
        }
        try {
            if (!sdk.send(request.state)) throw WeChatAccountException(WeChatAccountException.Reason.UNAVAILABLE)
            val reply = withTimeoutOrNull(TIMEOUT_MS) {
                // Keep timeout distinct from a successful delivery of a cancelled reply.
                Result.success(request.result.await())
            } ?: throw WeChatAccountException(WeChatAccountException.Reason.AUTHORIZATION_FAILED)
            reply.getOrThrow()
        } finally {
            pending.compareAndSet(request, null)
            request.result.cancel()
        }
    }

    fun onForeground() {
        val request = pending.get() ?: return
        if (request.result.isCompleted) return
        val failure = pendingFailure(request) ?: return
        request.result.completeExceptionally(WeChatAccountException(failure))
    }

    fun handle(intent: Intent) {
        val request = pending.get() ?: return
        if (request.result.isCompleted) return
        // The SDK validates its callback envelope. An arbitrary URL or Intent extra cannot supply a code.
        runCatching {
            driver?.handle(intent) { status, state, code -> receive(request, status, state, code) }
        }
    }

    private fun receive(request: Pending, status: ResponseStatus, state: String?, code: String?) {
        if (pending.get() !== request || request.result.isCompleted || request.state != state) return
        // The callback Activity can run before the original screen observes a package change.
        val failure = pendingFailure(request)
        when {
            failure != null -> request.result.completeExceptionally(WeChatAccountException(failure))
            status == ResponseStatus.CANCELLED -> request.result.complete(null)
            status == ResponseStatus.AUTHORIZED && code != null && isWeChatAuthorizationCode(code) -> request.result.complete(code)
            else -> request.result.completeExceptionally(WeChatAccountException(WeChatAccountException.Reason.AUTHORIZATION_FAILED))
        }
    }

    private fun pendingFailure(request: Pending): WeChatAccountException.Reason? = when {
        !isAvailable -> WeChatAccountException.Reason.UNAVAILABLE
        elapsedMillis() >= request.deadline -> WeChatAccountException.Reason.AUTHORIZATION_FAILED
        else -> null
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}
