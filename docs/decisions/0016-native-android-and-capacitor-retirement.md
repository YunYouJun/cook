# 0016 原生 Android 与 Capacitor 外壳退役

- 状态：已采纳
- 日期：2026-10-26
- 关联：[#98](https://github.com/YunYouJun/cook/issues/98)、[#169](https://github.com/YunYouJun/cook/issues/169)、[Android 分阶段计划](../dev/android-roadmap)
- 取代范围：[0003](./0003-native-ios-monorepo) 中等待 iOS 上架后清理外壳及 Android 去留另定的安排、[0006](./0006-ios-bundle-id) 中暂留旧外壳及其域名关联的安排

## 背景

原生 SwiftUI iOS 已在 `apps/ios` 独立维护，Web 在 `apps/web` 继续服务浏览器和云乐坊容器。根目录的 Capacitor iOS / Android 工程、脚本和 Web 内的插件调用已成为另一套维护负担。维护者决定现在清理旧外壳，Android 后续使用 Kotlin + Jetpack Compose 原生开发，Web 继续保留 Ionic。

## 方案对比

| 方案                                   | 取舍                                                                       |
| -------------------------------------- | -------------------------------------------------------------------------- |
| 继续维护 Capacitor 外壳                | 可复用网页，但要继续维护 WebView 适配、插件与两套原生壳工程                |
| Kotlin + Jetpack Compose               | 独立原生界面与系统集成；需要 Kotlin 实现核心逻辑，并用共享用例控制三端差异 |
| 同时引入 Kotlin Multiplatform 改造 iOS | 可以共享部分代码，但扩大现有 SwiftUI / CookCore 的改造范围，当前阶段不采用 |

## 决定

- 清理历史 `ios/`、`android/`、Capacitor 配置、打包命令、插件依赖和 Web 原生分支，不等待 iOS 正式上架；旧外壳不再继续开发。
- 保留 Nuxt + Ionic Web 页面、导航、样式和云乐坊宿主适配。`@nuxtjs/ionic` 1.0.2 未使用的原生工具依赖通过 pnpm overrides 排除，不重写其 Web 集成。
- Android 新工程放在 `apps/android`，使用 Kotlin、Compose、Material 3、ViewModel / StateFlow、Navigation 3、Room 和 DataStore。首轮只规划，不创建空工程或声称已有可安装包。
- 共享 `packages/data`、Schema、固定菜谱 id、备份协议和 fixtures；搜索、推荐、整句识别等逻辑用 Kotlin 移植。现有 TypeScript 和 Swift 实现继续维护，不在 Android 内嵌 JavaScript 执行核心逻辑。
- 先交付本地可用的做菜流程和文件迁移，再实现账号及跨平台同步。账号登录与数据同步分别验收；CloudKit 维持 Apple 设备范围。
- 保留现有原生 iOS 的 HTTPS SSO 回调、Web 回调兜底和 AASA 原生条目，只移除旧 Capacitor 应用的 AASA 条目；不修改服务端 SSO 登记或生产部署。

## 影响

- 根目录旧 `cap` 命令停止提供；iOS 按 `apps/ios/README.md` 构建，Android 工程启动后提供独立 Gradle 命令。
- 核心规则变更在 Android 实现后需要同时维护 TS / Swift / Kotlin，并以共享 fixtures 验证结果及排序。
- Android 包名、正式签名、历史分发与用户升级路径需在启动阶段核对，删除旧源码不代表用户数据已迁移。
- 实施顺序和各阶段退出条件见 [Android 开发方案与计划](../dev/android-roadmap)。
