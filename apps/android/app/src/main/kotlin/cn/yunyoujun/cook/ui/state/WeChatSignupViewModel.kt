package cn.yunyoujun.cook.ui.state

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.yunyoujun.cook.data.remote.WECHAT_SIGNUP_OTP
import cn.yunyoujun.cook.data.remote.WECHAT_SIGNUP_PHONE
import cn.yunyoujun.cook.data.remote.WeChatAccountException
import cn.yunyoujun.cook.data.remote.WeChatSignupNext
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID
import kotlin.coroutines.coroutineContext

private const val SIGNUP_SPACES = " \u00a0\u202f"

internal sealed interface SignupStep {
    data object Phone : SignupStep
    data class Sms(val challenge: String) : SignupStep
    data class Confirm(val proof: String) : SignupStep
    data object Existing : SignupStep
    data object Created : SignupStep
    data object Unconfirmed : SignupStep
}

internal data class SignupPicture(val bitmap: ImageBitmap, val challenge: String)

internal data class SignupFormState(
    val step: SignupStep = SignupStep.Phone,
    val phone: String = "",
    val otp: String = "",
    val picture: SignupPicture? = null,
    val captchaAnswer: String = "",
    val failure: String? = null,
    val working: Boolean = false,
    val registering: Boolean = false,
    val authorizing: Boolean = false,
    val resendAt: Long = 0L,
)

/** Retains transient verification state in the destination's ViewModel only. */
internal class WeChatSignupViewModel(
    private val service: YunlefunAccountService,
    private val authorize: suspend () -> String?,
    private val register: suspend (proof: String, deviceId: String, authorize: suspend () -> String?) -> Unit,
) : ViewModel() {
    private var deviceId = UUID.randomUUID().toString()
    private val mutableState = MutableStateFlow(SignupFormState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    fun changePhone(value: String) {
        if (!state.value.working) {
            val input = value.take(64)
            val compact = input.filterNot { it in SIGNUP_SPACES || it in "-()" }
            val national = when {
                compact.startsWith("+86") -> compact.drop(3)
                compact.startsWith("86") && compact.length == 13 -> compact.drop(2)
                else -> compact
            }
            // Keep malformed input visible. A pasted extension must not become a valid prefix.
            val phone = national.takeIf { input.length == value.length && WECHAT_SIGNUP_PHONE.matches(it) } ?: input
            mutableState.value = state.value.copy(phone = phone)
        }
    }

    fun changeOtp(value: String) {
        if (!state.value.working) {
            val input = value.take(32)
            val compact = input.filterNot { it in SIGNUP_SPACES }
            val otp = compact.takeIf { input.length == value.length && WECHAT_SIGNUP_OTP.matches(it) } ?: input
            mutableState.value = state.value.copy(otp = otp)
        }
    }

    fun changeCaptchaAnswer(value: String) {
        if (!state.value.working) {
            mutableState.value = state.value.copy(
                captchaAnswer = value.filter { it.isLetterOrDigit() && it.code < 128 }.take(6),
            )
        }
    }

    fun reset() {
        if (!state.value.working) resetVerification()
    }

    private fun resetVerification() {
        deviceId = UUID.randomUUID().toString()
        mutableState.value = state.value.copy(
            step = SignupStep.Phone,
            otp = "",
            picture = null,
            captchaAnswer = "",
            failure = null,
            resendAt = 0L,
        )
    }

    fun reloadPicture() = runWork { loadPicture() }

    fun sendSms() {
        val form = state.value
        if (form.step !is SignupStep.Phone && form.step !is SignupStep.Sms) return
        if (!WECHAT_SIGNUP_PHONE.matches(form.phone) ||
            (form.step is SignupStep.Sms && SystemClock.elapsedRealtime() < form.resendAt)
        ) return
        if (form.picture != null && form.captchaAnswer.length !in 4..6) return
        runWork {
            val answer = form.picture?.let { it.challenge to form.captchaAnswer }
            try {
                val challenge = service.sendWeChatSignupSms(form.phone, answer, deviceId)
                coroutineContext.ensureActive()
                mutableState.value = state.value.copy(
                    step = SignupStep.Sms(challenge),
                    otp = "",
                    picture = null,
                    captchaAnswer = "",
                    resendAt = SystemClock.elapsedRealtime() + 60_000L,
                )
            } catch (error: WeChatAccountException) {
                if (error.reason != WeChatAccountException.Reason.CAPTCHA_REQUIRED &&
                    error.reason != WeChatAccountException.Reason.CAPTCHA_INVALID
                ) throw error
                coroutineContext.ensureActive()
                loadPicture()
                if (error.reason == WeChatAccountException.Reason.CAPTCHA_INVALID) {
                    mutableState.value = state.value.copy(failure = error.message)
                }
            }
        }
    }

    fun verifyPhone() {
        val form = state.value
        val step = form.step as? SignupStep.Sms ?: return
        if (!WECHAT_SIGNUP_OTP.matches(form.otp)) return
        runWork {
            val next = service.verifyWeChatSignupSms(step.challenge, form.otp, deviceId)
            coroutineContext.ensureActive()
            val target = when (next) {
                WeChatSignupNext.ExistingAccount -> SignupStep.Existing
                is WeChatSignupNext.Confirm -> SignupStep.Confirm(next.proof)
            }
            mutableState.value = state.value.copy(step = target, otp = "", picture = null, captchaAnswer = "")
        }
    }

    fun createAccount() {
        val step = state.value.step as? SignupStep.Confirm ?: return
        runWork(registering = true) {
            register(step.proof, deviceId) {
                mutableState.value = state.value.copy(authorizing = true)
                try {
                    authorize()
                } finally {
                    mutableState.value = state.value.copy(authorizing = false)
                }
            }
        }
    }

    fun cancelAuthorization() {
        if (state.value.authorizing) operation?.cancel()
    }

    private suspend fun loadPicture() {
        mutableState.value = state.value.copy(captchaAnswer = "")
        val captcha = service.weChatSignupCaptcha(deviceId)
        val image = withContext(Dispatchers.Default) { decodeSignupPicture(captcha.image) }
        mutableState.value = state.value.copy(picture = SignupPicture(image, captcha.challenge))
    }

    private fun runWork(registering: Boolean = false, work: suspend () -> Unit) {
        if (state.value.working) return
        mutableState.value = state.value.copy(working = true, registering = registering, failure = null)
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ensureActive()
                if (error is WeChatAccountException) {
                    when (error.reason) {
                        WeChatAccountException.Reason.EXISTING_ACCOUNT ->
                            mutableState.value = state.value.copy(step = SignupStep.Existing)
                        WeChatAccountException.Reason.ACCOUNT_CREATED ->
                            mutableState.value = state.value.copy(step = SignupStep.Created)
                        WeChatAccountException.Reason.SIGNUP_UNCONFIRMED ->
                            mutableState.value = state.value.copy(step = SignupStep.Unconfirmed)
                        WeChatAccountException.Reason.RESTART_SIGNUP -> {
                            resetVerification()
                            mutableState.value = state.value.copy(failure = error.message)
                        }
                        WeChatAccountException.Reason.UNAVAILABLE ->
                            mutableState.value = state.value.copy(failure = "微信授权暂不可用，请稍后重试。")
                        else -> mutableState.value = state.value.copy(failure = error.message)
                    }
                } else {
                    mutableState.value = state.value.copy(failure = weChatUiMessage(error))
                }
            } finally {
                mutableState.value = state.value.copy(working = false, registering = false)
                operation = null
            }
        }
        operation = job
        job.start()
    }

    override fun onCleared() {
        mutableState.value = SignupFormState()
    }
}

private fun decodeSignupPicture(uri: String): ImageBitmap {
    val prefix = uri.substringBefore(',')
    val encoded = uri.substringAfter(',', "")
    if (prefix !in setOf("data:image/gif;base64", "data:image/png;base64", "data:image/jpeg;base64") ||
        encoded.length !in 1..200_000
    ) throw YunlefunAccountException.InvalidResponse()
    val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrElse { throw YunlefunAccountException.InvalidResponse() }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..512) throw YunlefunAccountException.InvalidResponse()
    val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw YunlefunAccountException.InvalidResponse()
    return image.asImageBitmap()
}
