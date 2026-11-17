# 云食册 Android

原生 Android 预览版。 `:app` 使用 Kotlin、Jetpack Compose、Navigation 3 和 Room，`:core` 是纯 Kotlin/JVM 模块，和 Web、iOS 共用菜谱数据与测试样例。

## 构建

使用 JDK 21、Android SDK Platform 36 和 Build Tools 35.0.0。Gradle、AGP 和 Kotlin 版本由仓库里的 Wrapper 和 Version Catalog 固定。

在仓库根目录运行：

```sh
pnpm install --frozen-lockfile
pnpm convert
cd apps/android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `fun.yunle.cook.debug`。Release APK 启用 R8 和资源压缩，输出为未签名包。

## 验收范围

找菜、食材筛选、查看详情和本地收藏可以断网使用。[三端共享契约](../../docs/dev/cross-platform-core-ci.md)说明了 Swift、TypeScript 和 Kotlin 的测试对照。正式签名、历史包迁移、App Links 与生产账号、反馈及同步联调按[路线图](../../docs/dev/android-roadmap.md)单独验收；构建通过不代表可发布。
