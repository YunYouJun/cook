# App 开发

Cook 在同一仓库维护 Web 和原生客户端，共享 `packages/data` 的菜谱、JSON Schema 和测试用例。客户端界面及核心逻辑分别使用各平台语言实现。

| 客户端             | 位置                  | 技术与状态                                           |
| ------------------ | --------------------- | ---------------------------------------------------- |
| Web / 云乐坊子应用 | `apps/web`            | Nuxt、Vue、Ionic；继续保留现有组件、导航和样式       |
| 原生 iOS「云食册」 | `apps/ios`            | SwiftUI、SwiftData、CloudKit；已提供 TestFlight 外测 |
| 原生 Android       | 规划为 `apps/android` | Kotlin、Jetpack Compose、Material 3；尚未创建工程    |

## 原生 iOS

Bundle ID 为 `fun.yunle.cook`。构建和测试见 [iOS README](https://github.com/YunYouJun/cook/blob/main/apps/ios/README.md)，实施与验收见 [iOS App 打磨目标](./ios-roadmap)。原生云乐坊账号登录已实现，详见 [iOS 账号](./ios-account)；登录账号不等于已实现 CloudBase 跨平台数据同步。

收藏、历史、私人菜谱及个人食材 / 厨具的文件协议见 [私人菜谱与文件迁移](./private-recipes)。这些协议分别处理，不混用，也不因登录而自动上传私人菜谱或个人词典。CloudKit 仅用于 Apple 设备；原生客户端的 CloudBase 跨平台同步仍是后续工作。

## 原生 Android

采用 Kotlin + Jetpack Compose，按共享核心、本地做菜流程、文件迁移、账号与同步、发布的顺序推进。技术栈、模块边界、各阶段待办和验收条件见 [Android 开发方案与计划](./android-roadmap)，选型依据见 [0016](../decisions/0016-native-android-and-capacitor-retirement)。

## Capacitor 外壳退役

2026-10-26 决定先清理历史 Capacitor 外壳，保留 Web 的 Ionic，并将 Android 后续开发切换到原生路线：

- 删除根目录 `ios/`、`android/`、`capacitor.config.ts`、旧 Ionic CLI 配置及 Capacitor 构建 / 调试脚本；原生 `apps/ios` 继续维护。
- 删除直接 `@capacitor/*` 依赖、原生状态栏与返回键分支、Capacitor SSO 适配及其测试。
- 保留 `@nuxtjs/ionic`。该模块 1.0.2 的 Web 集成不使用它声明的 Capacitor / Ionic CLI 依赖，通过限定版本的 pnpm overrides 排除这些包；升级模块时重新检查依赖与构建。
- 保留普通浏览器与云乐坊容器授权、外链、安全区和宿主能力检测，保留 Web 原有域名、本机数据及文件迁移入口。

### HTTPS 登录回调

`/auth/callback?platform=native` 仍被 SwiftUI 的系统授权会话使用。Web 收到该回调时只清除 URL 授权片段并提示返回应用或网页版，不把它当作 Web 授权消费。`cook-mobile` 的服务端登记不因清理 Capacitor 而撤销。

站点 AASA 保留原生 `QT792GAY4C.fun.yunle.cook` 的 `webcredentials` 和 `/auth/wechat/*` applinks，移除旧外壳 `N3885VFNCL.cn.yunyoujun.cook` 的关联。源码更新需要随站点发布后才会在线上生效，不表示本轮已部署。

未来 Android 的包名、分发渠道、签名和升级路径在原生工程启动阶段核对；其 App Links 使用最终发布证书与精确回调，不沿用旧外壳的登记作为已验证结果。
