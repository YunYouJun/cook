package cn.yunyoujun.cook.ui.screens

import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.data.remote.WECHAT_PRIVACY_URL
import cn.yunyoujun.cook.data.remote.WECHAT_SIGNUP_PHONE
import cn.yunyoujun.cook.data.remote.WeChatAccountException
import cn.yunyoujun.cook.data.remote.WeChatSignupNext
import cn.yunyoujun.cook.data.remote.YunlefunAccountException
import cn.yunyoujun.cook.data.remote.YunlefunAccountService
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.WeChatAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.UUID

@Composable
internal fun WeChatAccountActions(
    authorization: WeChatAuthorization,
    sessions: YunlefunSessionManager,
    subject: String?,
    onOpenSignup: () -> Unit,
) {
    var available by remember(authorization) { mutableStateOf(authorization.isAvailable) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { available = authorization.isAvailable }
    if (!available) return

    val scope = rememberCoroutineScope()
    val awaiting by authorization.awaiting.collectAsState()
    var consent by remember(subject) { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var status by remember(subject) { mutableStateOf<String?>(null) }
    var failure by remember(subject) { mutableStateOf<String?>(null) }
    val binding = subject != null
    val title = if (binding) "绑定微信" else "使用微信登录"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !working, onClick = { consent = true }) { Text(title) }
            if (!binding && BuildConfig.COOK_WECHAT_SIGNUP_ENABLED) {
                TextButton(enabled = !working, onClick = onOpenSignup) { Text("使用微信注册") }
            }
        }
        if (working) {
            Text(
                if (awaiting) "等待微信授权…" else if (binding) "正在绑定…" else "正在登录…",
                style = MaterialTheme.typography.bodySmall,
            )
            if (awaiting) TextButton(onClick = authorization::cancelWaiting) { Text("取消等待授权") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (consent) {
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (binding) "继续后将打开微信，把微信关联到当前云乐坊账号。"
                        else "继续后将打开微信，登录已关联的云乐坊账号。首次使用可先登录原账号并绑定微信。",
                    )
                    WeChatPrivacyLink()
                }
            },
            confirmButton = {
                Button(onClick = {
                    consent = false
                    working = true
                    failure = null
                    status = null
                    scope.launch {
                        try {
                            if (binding) {
                                if (sessions.bindWeChat(authorization::authorize)) status = "已将微信绑定到当前云乐坊账号。"
                            } else {
                                sessions.signInWithWeChat(authorization::authorize)
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            failure = weChatUiMessage(error)
                        } finally {
                            working = false
                        }
                    }
                }) { Text("继续前往微信") }
            },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("取消") } },
        )
    }
}

private sealed interface SignupStep {
    data object Phone : SignupStep
    data class Sms(val challenge: String) : SignupStep
    data class Confirm(val proof: String) : SignupStep
    data object Existing : SignupStep
    data object Created : SignupStep
}

private data class SignupPicture(val bitmap: ImageBitmap, val challenge: String)

@Composable
internal fun WeChatSignupScreen(container: AppContainer, onBack: () -> Unit) {
    val state by container.yunlefunSessionManager.state.collectAsState()
    val authorization = container.weChatAuthorization
    val enabled = BuildConfig.COOK_WECHAT_SIGNUP_ENABLED
    LaunchedEffect(enabled, state.credential?.subject) {
        if (!enabled || state.credential != null) onBack()
    }
    if (!enabled || state.credential != null) return

    val awaiting by authorization.awaiting.collectAsState()
    WeChatSignupForm(
        service = container.yunlefunAccountService,
        register = { proof, deviceId ->
            container.yunlefunSessionManager.registerWithWeChat(proof, deviceId, authorization::authorize)
        },
        onBack = onBack,
        onCancelAuthorization = (authorization::cancelWaiting).takeIf { awaiting },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeChatSignupForm(
    service: YunlefunAccountService,
    register: suspend (proof: String, deviceId: String) -> Unit,
    onBack: () -> Unit,
    onCancelAuthorization: (() -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    // Phone, OTP and server challenges belong to this page and are kept in memory only.
    var deviceId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var step by remember { mutableStateOf<SignupStep>(SignupStep.Phone) }
    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf<SignupPicture?>(null) }
    var captchaAnswer by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var registering by remember { mutableStateOf(false) }
    var resendAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val remaining = ((resendAt - now + 999) / 1000).coerceAtLeast(0)
    LaunchedEffect(resendAt) {
        now = SystemClock.elapsedRealtime()
        while (now < resendAt) {
            delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }
    BackHandler(enabled = registering) {}

    fun reset() {
        deviceId = UUID.randomUUID().toString()
        step = SignupStep.Phone
        otp = ""
        picture = null
        captchaAnswer = ""
        failure = null
        resendAt = 0
    }

    suspend fun loadPicture() {
        captchaAnswer = ""
        val captcha = service.weChatSignupCaptcha(deviceId)
        val image = withContext(Dispatchers.Default) { decodeSignupPicture(captcha.image) }
        picture = SignupPicture(image, captcha.challenge)
    }

    fun runWork(work: suspend () -> Unit) {
        if (working) return
        working = true
        failure = null
        scope.launch {
            try {
                work()
            } catch (error: CancellationException) {
                throw error
            } catch (error: WeChatAccountException) {
                when (error.reason) {
                    WeChatAccountException.Reason.EXISTING_ACCOUNT -> step = SignupStep.Existing
                    WeChatAccountException.Reason.ACCOUNT_CREATED -> step = SignupStep.Created
                    WeChatAccountException.Reason.RESTART_SIGNUP -> {
                        reset()
                        failure = error.message
                    }
                    else -> failure = error.message
                }
            } catch (error: Exception) {
                failure = weChatUiMessage(error)
            } finally {
                working = false
                registering = false
            }
        }
    }

    fun sendSms() = runWork {
        val answer = picture?.let { it.challenge to captchaAnswer }
        try {
            val challenge = service.sendWeChatSignupSms(phone, answer, deviceId)
            picture = null
            captchaAnswer = ""
            otp = ""
            now = SystemClock.elapsedRealtime()
            resendAt = now + 60_000
            step = SignupStep.Sms(challenge)
        } catch (error: WeChatAccountException) {
            if (error.reason != WeChatAccountException.Reason.CAPTCHA_REQUIRED &&
                error.reason != WeChatAccountException.Reason.CAPTCHA_INVALID
            ) throw error
            step = SignupStep.Phone
            loadPicture()
            if (error.reason == WeChatAccountException.Reason.CAPTCHA_INVALID) failure = error.message
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("使用微信注册") },
            navigationIcon = { TextButton(enabled = !registering, onClick = onBack) { Text("返回") } },
        )
    }) { inside ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inside).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (val current = step) {
                SignupStep.Phone -> {
                    Text("先验证中国大陆手机号。已有云乐坊账号可以登录原账号后绑定微信。")
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter { digit -> digit in '0'..'9' }.take(11) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("中国大陆手机号") },
                        leadingIcon = { Text("+86") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        enabled = !working,
                    )
                    picture?.let { captcha ->
                        Image(captcha.bitmap, contentDescription = "图片验证码", modifier = Modifier.heightIn(max = 120.dp))
                        OutlinedTextField(
                            value = captchaAnswer,
                            onValueChange = { captchaAnswer = it.filter { char -> char.isLetterOrDigit() && char.code < 128 }.take(6) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("输入图片验证码") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                            singleLine = true,
                            enabled = !working,
                        )
                        TextButton(enabled = !working, onClick = { runWork { loadPicture() } }) { Text("换一张图片") }
                    }
                    Button(
                        enabled = !working && WECHAT_SIGNUP_PHONE.matches(phone) && (picture == null || captchaAnswer.length in 4..6),
                        onClick = ::sendSms,
                    ) { Text("发送短信验证码") }
                }
                is SignupStep.Sms -> {
                    Text("短信已发送至 +86 $phone。")
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { otp = it.filter { digit -> digit in '0'..'9' }.take(6) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("短信验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !working,
                    )
                    Button(enabled = !working && otp.length == 6, onClick = {
                        runWork {
                            val next = service.verifyWeChatSignupSms(current.challenge, otp, deviceId)
                            otp = ""
                            step = when (next) {
                                WeChatSignupNext.ExistingAccount -> SignupStep.Existing
                                is WeChatSignupNext.Confirm -> SignupStep.Confirm(next.proof)
                            }
                        }
                    }) { Text("验证手机号") }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = !working && remaining == 0L, onClick = ::sendSms) {
                            Text(if (remaining == 0L) "重新发送" else "重新发送（${remaining} 秒）")
                        }
                        TextButton(enabled = !working, onClick = ::reset) { Text("修改手机号") }
                    }
                }
                is SignupStep.Confirm -> {
                    Text("手机号已验证。继续后将打开微信，授权成功才会创建并绑定云乐坊账号。")
                    WeChatPrivacyLink()
                    Button(enabled = !working, onClick = {
                        registering = true
                        runWork {
                            register(current.proof, deviceId)
                        }
                    }) { Text("创建账号并授权微信") }
                    TextButton(enabled = !working, onClick = ::reset) { Text("修改手机号") }
                }
                SignupStep.Existing -> {
                    Text("此手机号已有云乐坊账号。请先登录原账号，再在账号详情绑定微信。")
                    Button(onClick = onBack) { Text("返回登录原账号") }
                    TextButton(onClick = ::reset) { Text("换个手机号") }
                }
                SignupStep.Created -> {
                    Text("账号已创建。请用已验证的手机号登录，再检查微信绑定。")
                    Button(onClick = onBack) { Text("返回登录") }
                }
            }
            if (working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (registering) "等待微信授权或正在创建账号…" else "正在验证…")
                if (registering && onCancelAuthorization != null) {
                    TextButton(onClick = onCancelAuthorization) { Text("取消等待授权") }
                }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun WeChatPrivacyLink() {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(WECHAT_PRIVACY_URL) } }) {
        Text("微信 OpenSDK 个人信息处理规则")
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

private fun weChatUiMessage(error: Exception): String =
    when (error) {
        is WeChatAccountException, is YunlefunAccountException -> error.message ?: "微信授权暂不可用，请稍后重试。"
        else -> "微信授权暂不可用，请稍后重试。"
    }
