package cn.yunyoujun.cook.data.remote

internal class WeChatAccountException(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val wire: String?, val message: String) {
        UNAVAILABLE(null, "微信登录暂不可用，请使用浏览器登录云乐坊账号。"),
        AUTHORIZATION_FAILED("wechat_code_invalid", "微信授权未完成，请重试。"),
        BINDING_REQUIRED("wechat_binding_required", "此微信尚未关联云食册。请先登录原来的云乐坊账号，再到账号详情绑定。"),
        BINDING_REAUTHORIZE(null, "云乐坊登录已续期。请重新发起微信授权，继续绑定。"),
        BINDING_CONFLICT("binding_conflict", "当前账号已绑定微信，或此微信已被绑定。请检查账号绑定状态。"),
        ACCOUNT_RESTRICTED("account_restricted", "此云乐坊账号暂不可用，请在云乐坊网站检查账号状态。"),
        PHONE_VERIFICATION_REQUIRED("phone_verification_required", "请先在云乐坊网站验证手机号，再使用微信登录。"),
        IDENTITY_UNVERIFIED("wechat_identity_unverified", "无法确认此微信与云乐坊账号的关联，请使用原来的登录方式。"),
        RATE_LIMITED("rate_limited", "操作过于频繁，请稍后重试。"),
        CAPTCHA_REQUIRED("signup_captcha_required", "请先完成图片验证。"),
        CAPTCHA_INVALID("signup_captcha_invalid", "图片验证码错误或已过期，请重试。"),
        INVALID_OTP("signup_otp_invalid", "短信验证码错误或已过期，请检查后重试。"),
        RESTART_SIGNUP("signup_restart_required", "本次验证已过期，请重新验证手机号。"),
        EXISTING_ACCOUNT("signup_existing_account", "此手机号已有云乐坊账号，请登录原账号再绑定微信。"),
        ACCOUNT_CREATED("signup_account_created", "账号已创建。请用已验证的手机号登录，再检查微信绑定。"),
        SIGNUP_UNCONFIRMED(null, "暂时无法确认是否开户。可尝试用已验证的手机号登录，再检查微信绑定，也可以重新验证手机号。"),
    }
}

internal data class WeChatSignupCaptcha(val image: String, val challenge: String)

internal sealed interface WeChatSignupNext {
    data object ExistingAccount : WeChatSignupNext
    data class Confirm(val proof: String) : WeChatSignupNext
}

internal const val WECHAT_PRIVACY_URL = "https://weixin.qq.com/cgi-bin/readtemplate?t=opensdk_agreement&lang=zh_CN"
internal val WECHAT_SIGNUP_PHONE = Regex("1[3-9][0-9]{9}")
internal val WECHAT_SIGNUP_OTP = Regex("[0-9]{6}")
internal fun isWeChatAuthorizationCode(code: String) = code.matches(Regex("[A-Za-z0-9_-]{1,512}"))
