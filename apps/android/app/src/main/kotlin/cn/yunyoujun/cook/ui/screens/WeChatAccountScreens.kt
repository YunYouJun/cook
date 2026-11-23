package cn.yunyoujun.cook.ui.screens

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PatternMatcher
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cn.yunyoujun.cook.AppContainer
import cn.yunyoujun.cook.BuildConfig
import cn.yunyoujun.cook.data.remote.WECHAT_PRIVACY_URL
import cn.yunyoujun.cook.data.remote.WECHAT_SIGNUP_OTP
import cn.yunyoujun.cook.data.remote.WECHAT_SIGNUP_PHONE
import cn.yunyoujun.cook.data.remote.WeChatAccountException
import cn.yunyoujun.cook.data.remote.YunlefunSessionManager
import cn.yunyoujun.cook.platform.WECHAT_PACKAGE
import cn.yunyoujun.cook.platform.WeChatAuthorization
import cn.yunyoujun.cook.ui.state.SignupFormState
import cn.yunyoujun.cook.ui.state.SignupStep
import cn.yunyoujun.cook.ui.state.WeChatAccountViewModel
import cn.yunyoujun.cook.ui.state.WeChatSignupViewModel
import kotlinx.coroutines.delay

@Composable
internal fun WeChatAccountActions(
    authorization: WeChatAuthorization,
    sessions: YunlefunSessionManager,
    subject: String?,
    revision: Long,
    onOpenSignup: () -> Unit,
) {
    if (!authorization.isEnabled) return
    val available = rememberWeChatAvailability(authorization)
    var consent by remember(revision) { mutableStateOf(false) }
    LaunchedEffect(available) {
        if (!available) consent = false
    }

    val model = viewModel { WeChatAccountViewModel(authorization, sessions) }
    val action by model.state.collectAsStateWithLifecycle()
    val currentAction = action.revision == revision
    if (!available && !(currentAction && (action.working || action.failure != null))) return
    BackHandler(enabled = action.authorizing && currentAction, onBack = model::cancelAuthorization)
    val binding = subject != null
    val title = if (binding) "绑定微信" else "使用微信登录"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (available) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !action.working, onClick = { consent = true }) { Text(title) }
                if (!binding && BuildConfig.COOK_WECHAT_SIGNUP_ENABLED) {
                    TextButton(enabled = !action.working, onClick = onOpenSignup) { Text("使用微信注册") }
                }
            }
        }
        if (action.working && currentAction) {
            Text(
                if (action.authorizing) "等待微信授权…" else if (binding) "正在绑定…" else "正在登录…",
                style = MaterialTheme.typography.bodySmall,
            )
            if (action.authorizing) TextButton(onClick = model::cancelAuthorization) { Text("取消等待授权") }
        }
        if (currentAction) {
            action.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            action.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (consent && available) {
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
                    model.confirm(revision)
                }) { Text("继续前往微信") }
            },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("取消") } },
        )
    }
}

@Composable
internal fun WeChatSignupScreen(container: AppContainer, onBack: () -> Unit) {
    val state by container.yunlefunSessionManager.state.collectAsState()
    val authorization = container.weChatAuthorization
    val enabled = BuildConfig.COOK_WECHAT_SIGNUP_ENABLED
    val weChatAvailable = rememberWeChatAvailability(authorization)
    LaunchedEffect(enabled, state.credential?.subject) {
        if (!enabled || state.credential != null) onBack()
    }
    if (!enabled || state.credential != null) return

    val model = viewModel {
        WeChatSignupViewModel(container.yunlefunAccountService, authorization::authorize) { proof, deviceId, authorize ->
            container.yunlefunSessionManager.registerWithWeChat(proof, deviceId, authorize)
        }
    }
    WeChatSignupForm(model, onBack, weChatAvailable)
}

@Composable
private fun rememberWeChatAvailability(authorization: WeChatAuthorization): Boolean {
    if (!authorization.isEnabled) return false
    val context = LocalContext.current
    var available by remember(authorization) { mutableStateOf(authorization.isAvailable) }
    LifecycleStartEffect(context, authorization) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.data?.schemeSpecificPart == WECHAT_PACKAGE ||
                    intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST)?.contains(WECHAT_PACKAGE) == true
                ) {
                    available = authorization.isAvailable
                    if (!available) authorization.onForeground()
                }
            }
        }
        val packageFilter = IntentFilter(Intent.ACTION_PACKAGE_CHANGED).apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
            addDataSchemeSpecificPart(WECHAT_PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        val suspensionFilter = IntentFilter(Intent.ACTION_PACKAGES_SUSPENDED).apply {
            addAction(Intent.ACTION_PACKAGES_UNSUSPENDED)
        }
        ContextCompat.registerReceiver(context, receiver, packageFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, receiver, suspensionFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        available = authorization.isAvailable
        onStopOrDispose { context.unregisterReceiver(receiver) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { available = authorization.isAvailable }
    return available
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WeChatSignupForm(
    model: WeChatSignupViewModel,
    onBack: () -> Unit,
    weChatAvailable: Boolean = true,
) {
    val form by model.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val working = form.working
    val registering = form.registering
    val canSubmitCaptcha = form.picture == null || form.captchaAnswer.length in 4..6
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val remaining = ((form.resendAt - now + 999) / 1000).coerceAtLeast(0)
    LaunchedEffect(form.resendAt) {
        now = SystemClock.elapsedRealtime()
        while (now < form.resendAt) {
            delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }
    BackHandler(enabled = registering, onBack = model::cancelAuthorization)

    val canSendSms = weChatAvailable && !working && canSubmitCaptcha && WECHAT_SIGNUP_PHONE.matches(form.phone) &&
        (form.step is SignupStep.Phone || (form.step is SignupStep.Sms && remaining == 0L))
    val canVerifyPhone = !working && form.step is SignupStep.Sms && WECHAT_SIGNUP_OTP.matches(form.otp)
    fun sendSms() {
        if (canSendSms) {
            focus.clearFocus()
            model.sendSms()
        }
    }

    fun verifyPhone() {
        if (canVerifyPhone) {
            focus.clearFocus()
            model.verifyPhone()
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("使用微信注册") },
                navigationIcon = { TextButton(enabled = !registering, onClick = onBack) { Text("返回") } },
            )
        },
    ) { inside ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inside).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!weChatAvailable && (form.step is SignupStep.Phone || form.step is SignupStep.Sms || form.step is SignupStep.Confirm)) {
                Text(
                    "微信暂不可用。安装或启用微信后可继续注册。",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when (form.step) {
                SignupStep.Phone -> {
                    Text("先验证中国大陆手机号。已有云乐坊账号可以登录原账号后绑定微信。")
                    OutlinedTextField(
                        value = form.phone,
                        onValueChange = model::changePhone,
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.PhoneNumberNational },
                        label = { Text("中国大陆手机号") },
                        leadingIcon = { Text("+86") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Phone,
                            imeAction = if (form.picture != null) ImeAction.Next else ImeAction.Send,
                        ),
                        keyboardActions = KeyboardActions(onSend = { sendSms() }),
                        singleLine = true,
                        enabled = !working,
                    )
                    SignupCaptcha(form, model, weChatAvailable, onSendSms = ::sendSms)
                    Button(enabled = canSendSms, onClick = ::sendSms) { Text("发送短信验证码") }
                }
                is SignupStep.Sms -> {
                    Text("短信已发送至 +86 ${form.phone}。")
                    OutlinedTextField(
                        value = form.otp,
                        onValueChange = model::changeOtp,
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode },
                        label = { Text("短信验证码") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { verifyPhone() }),
                        singleLine = true,
                        enabled = !working,
                    )
                    Button(enabled = canVerifyPhone, onClick = ::verifyPhone) {
                        Text("验证手机号")
                    }
                    if (form.picture != null) {
                        Text("完成图片验证后可重新发送短信。", style = MaterialTheme.typography.bodySmall)
                        SignupCaptcha(form, model, weChatAvailable, onSendSms = ::sendSms)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(enabled = canSendSms, onClick = ::sendSms) {
                            Text(if (remaining == 0L) "重新发送" else "重新发送（${remaining} 秒）")
                        }
                        TextButton(enabled = !working, onClick = model::reset) { Text("修改手机号") }
                    }
                }
                is SignupStep.Confirm -> {
                    Text("手机号已验证。继续后将打开微信，授权成功才会创建并绑定云乐坊账号。")
                    WeChatPrivacyLink()
                    Button(enabled = weChatAvailable && !working, onClick = model::createAccount) {
                        Text("创建账号并授权微信")
                    }
                    TextButton(enabled = !working, onClick = model::reset) { Text("修改手机号") }
                }
                SignupStep.Existing -> {
                    Text("此手机号已有云乐坊账号。请先登录原账号，再在账号详情绑定微信。")
                    Button(onClick = onBack) { Text("返回登录原账号") }
                    TextButton(onClick = model::reset) { Text("换个手机号") }
                }
                SignupStep.Created -> {
                    Text("账号已创建。请用已验证的手机号登录，再检查微信绑定。")
                    Button(onClick = onBack) { Text("返回登录") }
                }
                SignupStep.Unconfirmed -> {
                    Text(WeChatAccountException.Reason.SIGNUP_UNCONFIRMED.message)
                    Button(onClick = onBack) { Text("返回登录") }
                    TextButton(onClick = model::reset) { Text("重新验证手机号") }
                }
            }
            if (working) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(when {
                    form.authorizing -> "等待微信授权…"
                    registering -> "正在处理注册…"
                    else -> "正在验证…"
                })
                if (form.authorizing) {
                    TextButton(onClick = model::cancelAuthorization) { Text("取消等待授权") }
                }
            }
            form.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SignupCaptcha(form: SignupFormState, model: WeChatSignupViewModel, weChatAvailable: Boolean, onSendSms: () -> Unit) {
    val captcha = form.picture ?: return
    Image(captcha.bitmap, contentDescription = "图片验证码", modifier = Modifier.heightIn(max = 120.dp))
    OutlinedTextField(
        value = form.captchaAnswer,
        onValueChange = model::changeCaptchaAnswer,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("输入图片验证码") },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Send,
        ),
        keyboardActions = KeyboardActions(onSend = { onSendSms() }),
        singleLine = true,
        enabled = !form.working && weChatAvailable,
    )
    TextButton(enabled = !form.working && weChatAvailable, onClick = model::reloadPicture) { Text("换一张图片") }
}

@Composable
private fun WeChatPrivacyLink() {
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(WECHAT_PRIVACY_URL) } }) {
        Text("微信 OpenSDK 个人信息处理规则")
    }
}
