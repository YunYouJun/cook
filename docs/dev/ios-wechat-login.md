# 云食册原生微信登录联调

当前代码仅为内部联调准备。普通构建默认关闭微信入口，也不下载或链接 OpenSDK。

## 开放平台

- 移动应用 AppID：`wxce6c7405dd756b9d`，Bundle ID：`fun.yunle.cook`。
- Universal Links：`https://cook.yunyoujun.cn/auth/wechat/`（保留末尾的斜杠）。源站及 Apple CDN 已在 [#221](https://github.com/YunYouJun/cook/issues/221) 验证文件，但该 issue 的云乐坊系统 SSO 回调不是微信 SDK 验收。
- 维护者已报告移动应用审核通过；进入「接口信息」确认微信登录权限状态。若显示未获得，在微信开放平台申请开通。不要把控制台会话链接或 AppSecret 写进 issue、Git、App 配置或日志。

## 云乐坊统一登录后端

App 使用移动应用 AppID 拉起微信。拿到授权码后，调用现有 `https://api.yunle.fun/sso-ticket`：首次绑定用 `bindCookWeChat`，并提供当前云乐坊会话；登录用 `exchangeCookWeChatCode`，取得原 UID 的 custom ticket。CloudBase 继续承载云乐坊账号和会话；不新增 OAuth 身份源，也不修改网站的 `wx_open`。

已有账号先用原方式登录云乐坊，在云食册「我的 → 云乐坊账号」选择绑定并在微信确认授权。服务端把微信换码得到的身份关联到当前云乐坊会话的 UID。之后退出，再用微信登录会回到同一个 UID；不会覆盖已有绑定。云乐坊网站的微信登录和绑定独立管理。普通微信登录遇到未绑定身份时只提示绑定，不会创建账号。

「使用微信注册」是单独的入口，客户端 `COOK_WECHAT_SIGNUP_ENABLED` 和服务端同名开关都默认关闭，并依赖对应的微信登录开关。先在 App 验证中国大陆手机号。已有账号会看到登录原账号再绑定的提示；未查到账号时，需要点「创建账号并授权微信」并完成授权才开户。若开户后连接中断，用户可用已验证的手机号登录，再检查微信绑定。

移动应用 AppSecret 只填在云乐坊后端 `sso-ticket` 云函数的私有环境变量 `COOK_WECHAT_APP_SECRET`；不用填 CloudBase 的「微信登录」配置，也不要发到聊天里。后端启用前需完成集合权限、注销清理和真实身份字段验证。

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
2. 使用未绑定的测试微信登录，确认没有新建用户。用没有网站微信绑定的现有云乐坊账号登录，在云食册账号详情完成绑定；退出后用微信登录，在本机比对两次 UID，再检查收藏同步和会员权益。重复绑定、冲突、受限账号与网络中断不能覆盖绑定或替换原有会话。
3. 记录构建号、签名、设备版本、微信版本与脱敏结果；授权码、token、OpenID 和 UnionID 不进记录。核对网站原有 `wx_open` 登录仍正常。
4. 上线前更新隐私政策和 App Store 隐私申报，按 [App Review 4.8](https://developer.apple.com/app-store/review/guidelines/#login-services) 核对等效登录方式。真机和服务端验证通过前保持默认关闭。
