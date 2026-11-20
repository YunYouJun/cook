package cn.yunyoujun.cook.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.CookApplication
import cn.yunyoujun.cook.data.remote.WeChatAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSSO
import cn.yunyoujun.cook.data.remote.isWeChatAuthorizationCode
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelbase.BaseResp.ErrCode
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.utils.ILog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

internal class WeChatAuthorization(
    private val enabled: Boolean,
    private val driver: Driver,
    private val elapsedMillis: () -> Long = SystemClock::elapsedRealtime,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    interface Driver {
        fun isInstalled(): Boolean
        fun send(state: String): Boolean
        fun handle(intent: Intent, onResponse: (Int, String?, String?) -> Unit)
    }

    private class Pending(val state: String, val deadline: Long) {
        val result = CompletableDeferred<String?>()
    }

    private val pending = AtomicReference<Pending?>()
    private val _awaiting = MutableStateFlow(false)
    val awaiting = _awaiting.asStateFlow()

    val isAvailable: Boolean
        get() = enabled && driver.isInstalled()

    suspend fun authorize(): String? = withContext(dispatcher) {
        if (!isAvailable) throw WeChatAccountException(WeChatAccountException.Reason.UNAVAILABLE)
        val request = Pending(YunlefunSSO.randomToken(), elapsedMillis() + TIMEOUT_MS)
        if (!pending.compareAndSet(null, request)) {
            throw WeChatAccountException(WeChatAccountException.Reason.UNAVAILABLE)
        }
        try {
            _awaiting.value = true
            if (!driver.send(request.state)) throw WeChatAccountException(WeChatAccountException.Reason.UNAVAILABLE)
            val reply = withTimeoutOrNull(TIMEOUT_MS) {
                // Keep timeout distinct from a successful delivery of a cancelled reply.
                Result.success(request.result.await())
            } ?: throw WeChatAccountException(WeChatAccountException.Reason.AUTHORIZATION_FAILED)
            reply.getOrThrow()
        } finally {
            pending.compareAndSet(request, null)
            _awaiting.value = false
            request.result.cancel()
        }
    }

    fun cancelWaiting() {
        pending.get()?.result?.complete(null)
    }

    fun handle(intent: Intent) {
        val request = pending.get() ?: return
        if (request.result.isCompleted) return
        // The SDK validates its callback envelope. An arbitrary URL or Intent extra cannot supply a code.
        runCatching { driver.handle(intent, ::receive) }
    }

    internal fun receive(status: Int, state: String?, code: String?) {
        val request = pending.get() ?: return
        if (request.state != state) return
        when {
            elapsedMillis() >= request.deadline -> request.result.completeExceptionally(
                WeChatAccountException(WeChatAccountException.Reason.AUTHORIZATION_FAILED),
            )
            status == ErrCode.ERR_USER_CANCEL || status == ErrCode.ERR_AUTH_DENIED -> request.result.complete(null)
            status == ErrCode.ERR_OK && code != null && isWeChatAuthorizationCode(code) -> request.result.complete(code)
            else -> request.result.completeExceptionally(WeChatAccountException(WeChatAccountException.Reason.AUTHORIZATION_FAILED))
        }
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
    }
}

internal class WeChatOpenSdkDriver(context: Context) : WeChatAuthorization.Driver {
    private val context = context.applicationContext
    private var api: IWXAPI? = null

    @Suppress("DEPRECATION")
    override fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.tencent.mm", 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun send(state: String): Boolean = runCatching {
        val instance = api ?: WXAPIFactory.createWXAPI(context, BuildConfig.COOK_WECHAT_APP_ID, true).also {
            it.setLogImpl(QuietWeChatLog)
            api = it
        }
        instance.isWXAppInstalled && instance.registerApp(BuildConfig.COOK_WECHAT_APP_ID) &&
            instance.sendReq(SendAuth.Req().apply { scope = "snsapi_userinfo"; this.state = state })
    }.getOrDefault(false)

    override fun handle(intent: Intent, onResponse: (Int, String?, String?) -> Unit) {
        api?.handleIntent(intent, object : IWXAPIEventHandler {
            override fun onReq(req: BaseReq) = Unit

            override fun onResp(resp: BaseResp) {
                if (resp is SendAuth.Resp) onResponse(resp.errCode, resp.state, resp.code)
            }
        })
    }

    private object QuietWeChatLog : ILog {
        override fun v(tag: String?, message: String?) = Unit
        override fun d(tag: String?, message: String?) = Unit
        override fun i(tag: String?, message: String?) = Unit
        override fun w(tag: String?, message: String?) = Unit
        override fun e(tag: String?, message: String?) = Unit
    }
}

/** Exposed through the application ID's .wxapi.WXEntryActivity alias required by WeChat. */
class WeChatCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.COOK_WECHAT_LOGIN_ENABLED) {
            (application as CookApplication).container.weChatAuthorization.handle(intent)
        }
        finish()
    }
}
