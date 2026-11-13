# 云食册原生微信登录联调

当前代码仅为内部联调准备。普通构建默认关闭微信入口，也不下载或链接 OpenSDK。

## 开放平台

- 移动应用 AppID：`wxce6c7405dd756b9d`，Bundle ID：`fun.yunle.cook`。
- Universal Links：`https://cook.yunyoujun.cn/auth/wechat/`（保留末尾的斜杠）。源站及 Apple CDN 已在 [#221](https://github.com/YunYouJun/cook/issues/221) 验证文件，但该 issue 的云乐坊系统 SSO 回调不是微信 SDK 验收。
- 维护者已报告移动应用审核通过；进入「接口信息」确认微信登录权限状态。若显示未获得，在微信开放平台申请开通。不要把控制台会话链接或 AppSecret 写进 issue、Git、App 配置或日志。

## CloudBase 身份源

生产 `yunlefun-8g7ybcxc7345c490` 的 `wx_open` 已供云乐坊网站扫码登录。给 iOS 新增独立的 OAuth 身份源 `cookwechatios`，在准备好联调前保持关闭；**不替换** `wx_open` 的 AppID、AppSecret 或回调。先在隔离环境验证平台支持所需的登录与绑定行为。

| 设置                | 值                                                                                               |
| ------------------- | ------------------------------------------------------------------------------------------------ |
| 认证源 ID / 类型    | `cookwechatios` / `OAUTH`                                                                        |
| Client ID / Secret  | 移动应用 AppID；对应 AppSecret 只录入服务端身份源                                                |
| 授权范围            | `snsapi_userinfo`；App 使用 OpenSDK 发起授权，不调用网页授权地址                                 |
| 换码 / 用户信息端点 | `https://api.weixin.qq.com/sns/oauth2/access_token` / `https://api.weixin.qq.com/sns/userinfo`   |
| 参数映射            | 核对 `client_id → appid`、`client_secret → secret`，用户信息请求带 `openid`                      |
| 账号策略            | 关闭自动注册、按邮箱或手机号自动关联、第三方 ID 作为 CloudBase UID、透传模式。保留原有账号限制。 |

换码由 [CloudBase 第三方授权接口](https://docs.cloudbase.net/http-api/auth/auth-grant-provider-token) 完成。App 提交一次性授权码，不提交 AppSecret、微信标识或目标 UID；登录明确传 `force_disable_sign_up: true` 和 `sync_profile: false`。首次绑定必须先登录已有账号，再在账号详情明确选择绑定；App 使用已登录账号的 bearer 请求 [绑定接口](https://docs.cloudbase.net/http-api/auth/user-bind-with-provider)。App 通过现有 account-api 检查账号状态。

网站和原生各有身份源。网站已绑定微信不表示原生也已绑定，不用昵称、头像或跨 AppID 的 OpenID 推断两者归属。上线前必须确认云开发按服务端验证过的身份执行唯一性约束，且并发绑定或已绑定不同 UID 时不会改写已有绑定；若独立身份源不能保证这一点，保持入口关闭，先在账号服务补充跨源冲突校验。对已使用网站微信绑定的测试账号，还要核对网站、原有云乐坊登录和移动端绑定后微信登录取得同一个 CloudBase UID。

## 构建和验收

从仓库根目录生成菜谱数据并重新生成 Xcode 工程。使用已配置的 Apple 开发签名，在本地真机联调构建中显式开启：

```sh
pnpm convert
xcodegen generate --spec apps/ios/project.yml
xcodebuild -project apps/ios/Cook.xcodeproj -scheme Cook \
  -destination 'generic/platform=iOS' COOK_WECHAT_LOGIN_ENABLED=YES build
```

启用的 iPhoneOS 构建下载腾讯发布的 OpenSDK 2.0.9 ZIP，校验固定 SHA-256 后链接设备切片，并将 SDK 的隐私清单随包带上。离线构建可通过 `COOK_WECHAT_SDK_ARCHIVE` 指向相同 ZIP；模拟器和关闭开关的构建都不需要它。

1. 安装签名构建。在已安装微信的 iPhone 上，从「我的」触发授权，分别验证成功、取消、拒绝、冷启动无待处理事务和热回跳。没有微信时保留原云乐坊入口。
2. 使用未绑定的测试微信登录，确认没有新建用户。用已有云乐坊账号登录，在账号详情完成绑定；退出后用微信登录，逐项比对 UID、收藏同步、会员权益与原账号。重复绑定、冲突、受限账号与网络中断不能覆盖绑定或替换原有会话。
3. 记录构建号、签名、设备版本、微信版本与脱敏结果；授权码、token、OpenID 和 UnionID 不进记录。核对网站原有 `wx_open` 登录仍正常。
4. 上线前更新隐私政策和 App Store 隐私申报，按 [App Review 4.8](https://developer.apple.com/app-store/review/guidelines/#login-services) 核对等效登录方式。真机和服务端验证通过前保持默认关闭。
