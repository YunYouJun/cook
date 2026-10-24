# 微信关联域名发布与验证

核验日期：2026-10-25（北京时间）。隐私政策和站点、签名配置已发布；**Universal Links 真机自动打开 App 尚未验收通过，微信 SDK 及授权登录尚未接入**。

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

## 未通过项与证据边界

源站在本地默认解析和 Google 公共 DNS 返回的海外地址均返回正确 AASA。Apple 关联域名 CDN 在公共 DNS 返回的海外节点也已返回相同 JSON，但本地默认 CDN 节点反复返回 HTTP 404，并带有：

```text
Apple-Failure-Reason: SWCERR00301 Timeout
Apple-Failure-Details: {"cause":"Connection timed out"}
Apple-Try-Direct: true
```

这证明不同 CDN 节点的结果尚不一致，不能把海外节点成功等同于所有设备已生效。未修改 DNS、网络代理、设备安全设置，也未使用 `?mode=developer` 代替生产关联校验。

真机从本机临时备忘录点击该链接，实际打开 Chrome 的网页兜底页；链接菜单没有显示云食册专用打开项。临时便笺已移入最近删除，未清空最近删除或修改用户已有便笺。该结果不构成 Universal Links 跳转通过，也不构成微信授权测试。

后续需要默认 Apple CDN 正常返回新 AASA，再刷新安装关联并复测系统链接。不要通过卸载 App 清缓存，以免丢失本机做菜数据。微信 AppID 与登录权限获批后，还需接入 OpenSDK 的注册和回调处理、验证服务端身份映射与同一云乐坊账号绑定，最后测试“云食册 → 微信 → 云食册”。

Apple 说明关联文件缓存和设备更新并非即时，详见 [Supporting associated domains](https://developer.apple.com/documentation/xcode/supporting-associated-domains) 与 [TN3155: Debugging universal links](https://developer.apple.com/documentation/technotes/tn3155-debugging-universal-links)。缓存说明不能替代上述实际失败结果。

## 构建与验证

VitePress 完整文档构建、Nuxt 完整静态站构建及 iOS Release 构建通过。GitHub 的 lint、类型检查、网页构建、网页测试、Swift 检查已通过；发布时 iOS CI 尚在排队或执行中，不能声称该项已通过。生产网页和签名按本次源文件分别核验，不复用旧版本成功截图。

构建在任务独占临时目录完成，复用现有依赖。签名报告、响应头和页面截图保留为验证材料，可重建的站点与 Xcode 中间产物在结束后回收。
