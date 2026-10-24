# 微信关联域名发布与验证

核验日期：2026-10-25（北京时间）。隐私政策和站点、签名配置已发布；**6.6 版已通过 iPhone 15 Pro 的 Universal Links 热启动、冷启动回跳验证。微信 SDK 及授权登录尚未接入，Apple CDN 部分节点仍返回超时**。

## 开放平台填写值

| 字段            | 值                                       |
| --------------- | ---------------------------------------- |
| iOS Bundle ID   | `fun.yunle.cook`                         |
| Universal Links | `https://cook.yunyoujun.cn/auth/wechat/` |
| 隐私政策        | `https://docs.cook.yunle.fun/privacy`    |
| Apple Team      | `QT792GAY4C`                             |

Universal Links 保留结尾 `/`；不要用云乐坊 SSO 的 `/auth/callback?platform=native` 代替。

## 已发布内容

- PR [#151](https://github.com/YunYouJun/cook/pull/151) 已合并至主线，提交 `fa37466c846ccfd2923ec5650bf658f4f295eaed`。发布范围只包含隐私政策、AASA、iOS 工程权限、静态回跳页及响应头，不包含 #149 的账号界面或 #142 的同步修复。
- Cloudflare Pages `cook-docs` 生产部署 `4871d184-d7fb-4bca-864a-1f6dc3a825ef` 已回读确认 `main` / Production。正式自定义域的隐私政策在浏览器显示 2026-10-25 版，包含云乐坊账号、头像、设备内凭据、注销、iCloud 数据边界及微信 OpenSDK 尚未启用的说明。
- EdgeOne `yunyoujun-cook` 生产部署 `dpbe84c98zk4` 发布完整站点，保留原有页面与响应头。AASA 为 HTTPS 200、`application/json`、无重定向；新增原生 `/auth/wechat/*`，保留已有 `webcredentials` 和 Capacitor `/auth/callback`。
- `/auth/wechat/` 为 HTTPS 200 的静态兜底页，不执行脚本或统计，不读取授权参数，响应使用 `Cache-Control: no-store` 和 `Referrer-Policy: no-referrer`。
- 原生 `project.yml` 声明 `webcredentials:cook.yunyoujun.cn` 与 `applinks:cook.yunyoujun.cn`。Apple 描述文件允许 Associated Domains；Release 开发签名包 `2.0.0 (6.5)` 构建与 `codesign --verify --deep --strict` 通过，原位安装到 iPhone 15 Pro，未上传 TestFlight。包内仍使用 `iCloud.fun.yunle.cook` / `Production`。

## 真机回跳与证据边界

6.5 版从本机备忘录点击链接时进入 Chrome 兜底页，未通过验收。继续检查发现原生 SwiftUI 根视图没有 Universal Links 接收入口。6.6 版使用 `.onOpenURL` 接收链接，校验 HTTPS、域名、端口及 `/auth/wechat/` 路径后打开「我的」。不读取链接中的令牌或用户 ID 来改变登录状态，也不记录查询参数。Apple 的 [onOpenURL 文档](<https://developer.apple.com/documentation/swiftui/view/onopenurl(perform:)>) 说明 SwiftUI 的 Universal Links 通过此入口接收。

2026-10-25 02:28–02:29，Release 开发签名包 `2.0.0 (6.6)` 原位安装到 iPhone 15 Pro / iOS 27.0.1 后完成：

1. App 已运行且位于「做菜」时，从本机临时备忘录点击登记链接，系统直接打开云食册「我的」。
2. 返回「做菜」，结束 App 进程，再从备忘录点击同一链接，冷启动仍直接进入「我的」，随后恢复既有云乐坊头像和会话。
3. 原有冰箱食材仍显示；签名保持 `iCloud.fun.yunle.cook` / `Production`。本轮未验证两设备同步，未上传 TestFlight。

两次成功均使用生产 `applinks:cook.yunyoujun.cn`，未使用 `?mode=developer`、自定义 scheme 或命令行传入链接代替系统点击。临时便笺已移入最近删除，未清空最近删除或改动已有便笺。

这验证了当前设备与 6.6 客户端的关联和路由。由于本轮也重新安装了包，不能仅据此认定旧版失败完全由缺少接收入口造成；未取得设备 `swcd` 日志，也不能确定该设备使用了哪个 CDN 节点或是否直接回源。

## CDN 排查与部署选择

源站在本地默认解析和 Google 公共 DNS 返回的海外地址均返回正确 AASA。Apple 关联域名 CDN 在公共 DNS 返回的海外节点也已返回相同 JSON，但本地默认 CDN 节点反复返回 HTTP 404，并带有：

```text
Apple-Failure-Reason: SWCERR00301 Timeout
Apple-Failure-Details: {"cause":"Connection timed out"}
Apple-Try-Direct: true
```

2026-10-25 02:21 的定点探测结果：

| 探测对象             | 地址示例                            | HTTP 结果                  |
| -------------------- | ----------------------------------- | -------------------------- |
| EdgeOne 国内站点节点 | `112.28.174.246`                    | 200，正确 AASA             |
| EdgeOne 海外站点节点 | `43.174.247.34`                     | 200，正确 AASA             |
| Apple 国内节点       | `183.240.137.74`、`120.241.224.169` | 404，`SWCERR00301 Timeout` |
| Apple 海外节点       | `17.253.83.133`                     | 200，与站点 AASA 一致      |

探测通过单次 `curl --resolve` 保留正常 TLS 校验，不修改系统 DNS、代理或设备设置。结果证明 Apple 节点间仍存在差异，未能确定 Apple 内部抓取超时的具体网络原因；单台真机通过不代表所有地区的新安装都已生效。

当前保留 EdgeOne 的全球部署。站点本身已有国内及海外可达节点，换成仅国内 CDN 不能直接替换或清除 Apple 管理的关联域名 CDN。Apple 要求 AASA 对所有 IP、地区和 User-Agent 可达，并说明没有直接使关联 CDN 缓存失效的选项，详见 [TN3155: Debugging universal links](https://developer.apple.com/documentation/technotes/tn3155-debugging-universal-links)。后续若其他设备失败，先用设备关联诊断和站点访问日志定位，不通过卸载 App 清缓存，以免丢失本机数据。

微信 AppID 与登录权限获批后，还需接入 OpenSDK 的注册和回调处理、验证服务端身份映射与同一云乐坊账号绑定，最后测试“云食册 → 微信 → 云食册”。本轮通过的是系统 Universal Links 回跳，不是微信授权测试。

## 构建与验证

VitePress 完整文档构建、Nuxt 完整静态站构建及 iOS Release 构建通过。GitHub 的 lint、类型检查、网页构建、网页测试、Swift 检查已通过；发布时 iOS CI 尚在排队或执行中，不能声称该项已通过。生产网页和签名按本次源文件分别核验，不复用旧版本成功截图。

构建在任务独占临时目录完成，复用现有依赖。签名报告、响应头和页面截图保留为验证材料，可重建的站点与 Xcode 中间产物在结束后回收。

6.6 增量验证：Release 真机构建与严格签名校验通过；3 项 Universal Links 测试和 5 项既有 SSO 回调测试全部通过，涵盖来源与路径校验、只导航不改变身份、有效换码及拒绝异常回调。上述真机截图与 CDN 响应头已保存为独立验证材料。
