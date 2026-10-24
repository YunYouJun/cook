# 原生 iOS 云乐坊账号

原生 App 的「我的」提供可选云乐坊登录、会话恢复、退出、账号管理和注销入口。微信 SDK 暂不接入，等移动应用审核通过后再添加。收藏、历史、偏好、冰箱、私人菜谱继续使用原有 SwiftData / CloudKit；云乐坊用户 ID 不参与这些模型的存储路径，也不加入推荐统计和访客反馈。

## 客户端登记

复用云乐坊已有的第一方移动客户端，不重复申请权限相同的客户端：

| 项目         | 值                                                        |
| ------------ | --------------------------------------------------------- |
| 业务 appId   | `cook`，由服务端登记派生                                  |
| SSO clientId | `cook-mobile`                                             |
| 权限         | `identity:bootstrap`                                      |
| 授权方式     | 显式同意                                                  |
| Provider     | `https://www.yunle.fun/auth/sso`                          |
| 回调         | `https://cook.yunyoujun.cn/auth/callback?platform=native` |
| 换码         | `https://api.yunle.fun/sso-ticket`                        |
| CloudBase    | `yunlefun-8g7ybcxc7345c490`                               |
| Bundle ID    | `fun.yunle.cook`                                          |
| Apple Team   | `QT792GAY4C`（本机 `.env` 中的原生 iOS 签名团队）         |

2026-10-24 核对云乐坊仓库的 production Registry，并实际调用生产 `resolveSsoAuthorization`：上述 `cook-mobile` 请求返回 HTTP 200、`ok: true`，权限与显式授权策略匹配。Registry 中展示名仍为「食用手册」。这里不改服务端安全策略，也不新建第二份用户身份。

## HTTPS 回调与发布前置条件

`ASWebAuthenticationSession.Callback.https` 从 iOS 17.4 起可用，需要 **webcredentials** 域名关联。最低安装版本继续为 iOS 17；17.0–17.3 仅账号入口提示升级，其他功能可正常使用。不降级到自定义 URL Scheme，也不在自建 WebView 中收集密码。

1. 在 Apple Developer 的 `fun.yunle.cook` 标识符开启 Associated Domains，并更新签名描述文件。
2. 将 `apps/web/public/.well-known/apple-app-site-association` 发布到 `https://cook.yunyoujun.cn/.well-known/apple-app-site-association`。新增 `webcredentials.apps` 的 `QT792GAY4C.fun.yunle.cook`；旧 Capacitor 的 `N3885VFNCL.cn.yunyoujun.cook` applinks 保留。
3. 用正确的 `APPLE_DEVELOPMENT_TEAM` 生成工程。`project.yml` 已声明 `webcredentials:cook.yunyoujun.cn`；如果改签名团队，必须同步修改站点 AASA。
4. 等 Apple 的关联域名缓存更新后，真机验证系统授权窗口和 HTTPS 回调。模拟器编译、协议测试均不能替代此步骤。

2026-10-24 已将 AASA 发布到 EdgeOne 生产项目 `yunyoujun-cook`（部署 `dpwz3nca44ld`）。生产域名与 Apple CDN 均返回 HTTP 200、`application/json`，内容一致且包含原生 `webcredentials`。Apple App ID `JJLY23S94L` 已回读确认开启 Associated Domains，Xcode 自动更新了开发描述文件。

真机联调包为 `2.0.0 (6.1)`，由最新主线整合已发布构建 6 的私人菜谱同步修复和本次登录代码生成。使用 Release 优化、开发签名原位安装；签名权限已核验 `webcredentials:cook.yunyoujun.cn` 和原有 `iCloud.fun.yunle.cook` 的 `Production` 环境。该包用于本机联调，尚未上传 TestFlight；完整真人登录回跳与重启恢复验收仍在进行。

## 会话与数据边界

- SSO v3：每次独立生成 256-bit nonce 和 PKCE verifier，S256 challenge 发给 Provider，verifier 只在本次内存事务中保存，10 分钟过期。
- 系统认证窗口只监听指定 HTTPS host/path；回调还要精确匹配 query、issuer、nonce 和授权码格式，拒绝附带 ticket、token、session 的结果。
- 授权码只交换一次，随后通过官方 `/auth/v1/signin/custom` 登录同一 CloudBase 账号。没有客户端 secret，也不将服务端身份断言用作账号凭据。
- 访问 `/auth/v1/user/me` 取得当前资料，拒绝匿名、停用或与 token subject 不一致的账号。刷新使用 `/auth/v1/token`，注销使用 `/auth/v1/user/signout`。
- 会话只写入 `WhenUnlockedThisDeviceOnly` 钥匙串，不可同步，不写 UserDefaults、SwiftData、日志或 URL。刷新轮换后先保存新凭据，再读取资料；离线可重试，认证失效清除本机凭据。
- 退出只清除账号会话，不删除做菜数据。账号切换也不隔离或迁移 iCloud 数据；产品文案与隐私政策须保持这一说明。

## 验证

`YunlefunAccountTests.swift` 覆盖 PKCE、原始客户端绑定、伪造/过期回调、取消、跨次回调、错误身份、刷新轮换、离线恢复和退出。UI 测试使用内存账号存储，不访问用户钥匙串。

真机验收：未登录与已有网站登录两种入口、授权/取消、手机号准入、杀进程后重登、会话过期、离线/恢复、账号停用、退出、同一设备更换云乐坊账号后 iCloud 数据保持不变。另需核验账号管理/注销入口和 App Store 隐私标签。

参考：[Apple 系统认证窗口](https://developer.apple.com/documentation/authenticationservices/aswebauthenticationsession)、[CloudBase 自定义登录](https://docs.cloudbase.net/authentication-v2/method/custom-login)。
