package cn.yunyoujun.cook.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.CookApplication
import cn.yunyoujun.cook.platform.WeChatAuthorization.ResponseStatus
import com.tencent.mm.opensdk.modelbase.BaseReq
import com.tencent.mm.opensdk.modelbase.BaseResp
import com.tencent.mm.opensdk.modelbase.BaseResp.ErrCode
import com.tencent.mm.opensdk.modelmsg.SendAuth
import com.tencent.mm.opensdk.openapi.IWXAPI
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler
import com.tencent.mm.opensdk.openapi.WXAPIFactory
import com.tencent.mm.opensdk.utils.ILog

internal fun createWeChatAuthorization(context: Context) = WeChatAuthorization(WeChatOpenSdkDriver(context))

private class WeChatOpenSdkDriver(context: Context) : WeChatAuthorization.Driver {
    private val context = context.applicationContext
    private var api: IWXAPI? = null

    @Suppress("DEPRECATION")
    override fun isInstalled(): Boolean = try {
        val app = context.packageManager.getApplicationInfo(WECHAT_PACKAGE, 0)
        app.enabled && (app.flags and ApplicationInfo.FLAG_SUSPENDED) == 0
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun send(state: String): Boolean = runCatching {
        val instance = api ?: WXAPIFactory.createWXAPI(context, BuildConfig.COOK_WECHAT_APP_ID, true).also {
            it.setLogImpl(QuietWeChatLog)
            api = it
        }
        instance.isWXAppInstalled && instance.registerApp(BuildConfig.COOK_WECHAT_APP_ID) &&
            instance.sendReq(SendAuth.Req().apply {
                scope = "snsapi_userinfo"
                this.state = state
            })
    }.getOrDefault(false)

    override fun handle(intent: Intent, onResponse: (ResponseStatus, String?, String?) -> Unit) {
        api?.handleIntent(intent, object : IWXAPIEventHandler {
            override fun onReq(req: BaseReq) = Unit

            override fun onResp(resp: BaseResp) {
                if (resp !is SendAuth.Resp) return
                val status = when (resp.errCode) {
                    ErrCode.ERR_OK -> ResponseStatus.AUTHORIZED
                    ErrCode.ERR_USER_CANCEL, ErrCode.ERR_AUTH_DENIED -> ResponseStatus.CANCELLED
                    else -> ResponseStatus.FAILED
                }
                onResponse(status, resp.state, resp.code)
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
        (application as CookApplication).container.weChatAuthorization.handle(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as CookApplication).container.weChatAuthorization.handle(intent)
        finish()
    }
}
