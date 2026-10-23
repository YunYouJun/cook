# App 开发

原生 iOS「云食册」位于 `apps/ios`，使用 SwiftUI、SwiftData 和 CloudKit，Bundle ID 为 `fun.yunle.cook`。构建和测试见 [iOS README](https://github.com/YunYouJun/cook/blob/main/apps/ios/README.md)，实施与验收见 [iOS App 打磨目标](./ios-roadmap)。Capacitor 版未在 App Store 上架，原生版以新 App 提交，决定见 [0006](../decisions/0006-ios-bundle-id)。

Web 位于 `apps/web`，同时服务浏览器和云乐坊容器。收藏和历史的文件导出 / 导入已支持 Web 与原生 iOS 之间的手动迁移；CloudKit 仅用于 Apple 设备，原生版接入云乐坊账号和 CloudBase 跨平台同步仍待实现。

## 历史 Capacitor 外壳

仓库暂时保留以下跨平台方案及 `ios/`、`android/`，待原生版上架与 Android 去留决定后清理（[#98](https://github.com/YunYouJun/cook/issues/98)）：

- [Capacitor](https://capacitorjs.com/)
- [Ionic](https://ionicframework.com/)
- [Nuxt Ionic](https://ionic.nuxtjs.org/)

### Capacitor 的云乐坊 SSO 回调

以下配置属于历史 Capacitor 外壳，不表示原生 SwiftUI App 已接入 SSO。该外壳使用系统浏览器打开云乐坊授权页，并通过已验证的 HTTPS Universal Links / App Links
返回 `https://cook.yunyoujun.cn/auth/callback?platform=native`。不要改用自定义 URL Scheme；
PKCE 能阻止授权码被旁路窃取，但不能阻止恶意 App 主动冒充公开客户端发起授权。

iOS 的 Associated Domains 与站点 `apple-app-site-association` 已纳入项目。Android 上架前还需用
Google Play App Signing 的发布证书 SHA-256 生成站点文件
`apps/web/public/.well-known/assetlinks.json`，关系必须限定为：

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "cn.yunyoujun.cook",
      "sha256_cert_fingerprints": ["<PLAY_APP_SIGNING_SHA256>"]
    }
  }
]
```

不要把本机 debug keystore 指纹发布到生产站点。完成证书配置后，需在真机验证已登录快捷授权、
显式同意、取消、返回键、超时、伪造回调和授权码重放。
