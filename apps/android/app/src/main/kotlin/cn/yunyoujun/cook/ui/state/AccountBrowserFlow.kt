package cn.yunyoujun.cook.ui.state

import android.content.ActivityNotFoundException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.data.local.CredentialVaultException
import cn.yunyoujun.cook.data.remote.AccountWebsiteLaunch
import cn.yunyoujun.cook.data.remote.AccountWebsitePurpose
import cn.yunyoujun.cook.data.remote.ApprovedAccountWebsite
import cn.yunyoujun.cook.data.remote.MembershipSession
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.data.remote.YunlefunSignInResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Delivers account authorization actions from the resumed Activity. */
internal class AccountBrowserFlow : ViewModel() {
    private sealed interface Delivery {
        class SignInPage(val url: String, val appNonce: String? = null) : Delivery
        class WebsiteStart(val launch: AccountWebsiteLaunch) : Delivery
        class AccountWebsite(val website: ApprovedAccountWebsite) : Delivery
        class Notice(val text: String) : Delivery
    }

    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val completion = MutableStateFlow<Delivery?>(null)

    fun beginSignIn(sessions: YunlefunSessionManager, preferApp: Boolean) = execute(::signInError) {
        if (preferApp) {
            val transaction = sessions.beginAppSsoAuthorization()
            Delivery.SignInPage(transaction.appAuthorizationUrl, appNonce = transaction.nonce)
        } else {
            Delivery.SignInPage(sessions.beginSsoAuthorization())
        }
    }

    fun continueSignInOnWeb(sessions: YunlefunSessionManager, nonce: String) = execute(::signInError) {
        Delivery.SignInPage(sessions.continueAppSignInOnWeb(nonce))
    }

    fun cancelAppSignIn(sessions: YunlefunSessionManager, nonce: String) = execute(::signInError) {
        sessions.cancelAppSignIn(nonce)
        null
    }

    fun completeSignIn(sessions: YunlefunSessionManager, url: String) = execute(::signInError) {
        when (val result = sessions.handleSsoCallbackUrl(url).getOrThrow()) {
            YunlefunSignInResult.SignedIn -> null
            is YunlefunSignInResult.ContinueInBrowser -> Delivery.SignInPage(result.url)
        }
    }

    fun beginWebsite(
        sessions: YunlefunSessionManager,
        purpose: AccountWebsitePurpose,
        expectedSession: MembershipSession? = null,
    ) = execute(::websiteError) {
        Delivery.WebsiteStart(sessions.prepareAccountWebsite(purpose, expectedSession))
    }

    fun approveWebsite(sessions: YunlefunSessionManager, url: String, purpose: AccountWebsitePurpose? = null) =
        execute(::approvalError) { Delivery.AccountWebsite(sessions.approveAccountWebsiteCallback(url, purpose)) }

    fun resumeWebsite(sessions: YunlefunSessionManager, purpose: AccountWebsitePurpose) =
        execute(::websiteError) { Delivery.AccountWebsite(sessions.resumeApprovedAccountWebsite(purpose)) }

    private fun execute(errorMessage: (Exception) -> String?, action: suspend () -> Delivery?) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            val result = try {
                action()
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                errorMessage(error)?.let { Delivery.Notice(it) }
            }
            if (result != null) completion.value = result
            else mutableBusy.value = false
        }
    }

    suspend fun deliver(
        sessions: YunlefunSessionManager,
        open: (String) -> Unit,
        showMessage: (String) -> Unit,
        openSignInBrowser: (String) -> Unit = open,
        openSignInApp: (String) -> Boolean = { false },
    ) {
        completion.filterNotNull().collect { result ->
            try {
                when (result) {
                    is Delivery.SignInPage -> if (sessions.state.value.credential == null) {
                        val nonce = result.appNonce
                        if (nonce == null) {
                            openSignInBrowser(result.url)
                        } else if (sessions.state.value.appSignInNonce == nonce && !openSignInApp(result.url)) {
                            finishDelivery(result)
                            continueSignInOnWeb(sessions, nonce)
                        }
                    }
                    is Delivery.WebsiteStart -> sessions.launchAccountWebsite(result.launch, open)
                    is Delivery.AccountWebsite -> sessions.openApprovedAccountWebsite(result.website) { url ->
                        open(url)
                        finishDelivery(result)
                    }
                    is Delivery.Notice -> showMessage(result.text)
                }
            } catch (error: Exception) {
                // Preserve undelivered work if the Activity paused while opening the page.
                currentCoroutineContext().ensureActive()
                websiteError(error)?.let(showMessage)
            }
            finishDelivery(result)
        }
    }

    private fun finishDelivery(result: Delivery) {
        if (completion.compareAndSet(result, null)) mutableBusy.value = false
    }

    private fun signInError(error: Exception): String = when (error) {
        is CancellationException -> "已取消授权。"
        is YunlefunAccountException, is CredentialVaultException -> error.message.orEmpty()
        else -> "暂时无法完成登录，请从浏览器重新登录。"
    }

    private fun approvalError(error: Exception): String? = when (error) {
        is YunlefunAccountException.Unavailable, is YunlefunAccountException.InvalidResponse ->
            "暂时无法核对授权，请在有效期内用同一回调链接重试。"
        else -> websiteError(error)
    }

    private fun websiteError(error: Exception): String? = when (error) {
        is CancellationException -> null
        is ActivityNotFoundException -> "未找到可用的系统浏览器。"
        is CredentialVaultException -> error.message
        is YunlefunAccountException.ExpiredSession, is YunlefunAccountException.CheckoutUnavailable -> error.message
        is YunlefunAccountException.InvalidWebsiteCallback -> "授权无效或已过期，请从原入口重试。"
        else -> "无法打开云乐坊网页，请从原入口重试。"
    }
}
