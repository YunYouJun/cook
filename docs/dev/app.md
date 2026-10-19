# APP

考虑到跨平台和开发效率，计划使用：

- [Capacitor](https://capacitorjs.com/)
- [Ionic](https://ionicframework.com/)
- [Nuxt Ionic](https://ionic.nuxtjs.org/)

## 云乐坊 SSO 回调

移动端使用系统浏览器打开云乐坊授权页，并通过已验证的 HTTPS Universal Links / App Links
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
