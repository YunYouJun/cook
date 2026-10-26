# 共享产品资源

这里维护 Web 与 iOS 共用的链接和图标，不依赖任一端的组件或运行时。

- `links.json`：微信客服地址的唯一配置源。Web 的 `links.wecomKf` 导入该文件，iOS 的 `AppLinks.shared` 读取打包后的同一文件。
- `Assets.xcassets/wechat.imageset/icon.svg`：微信标识的唯一源文件。资产目录由 iOS 直接编译；Web 的 UnoCSS `i-cook-wechat` 直接读取其中的 SVG，按当前文字颜色显示。
- `Contents.json`：iOS 的矢量与模板渲染配置，不包含第二份图形。

修改链接或 SVG 后，分别重新构建 Web 和 iOS 即可生效，无需手动复制或维护两份内容。首次接入或工程资源列表变更后，运行 `xcodegen generate --spec apps/ios/project.yml`。

微信 SVG 来自 [Simple Icons](https://github.com/simple-icons/simple-icons/blob/develop/icons/wechat.svg)，采用 [CC0 1.0](https://github.com/simple-icons/simple-icons/blob/develop/LICENSE.md)。两端各自控制尺寸、颜色、间距及点击行为。
