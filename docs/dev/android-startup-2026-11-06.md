# Android 启动核对记录

- 日期：2026-11-06
- 关联：[#169](https://github.com/YunYouJun/cook/issues/169)、[路线图](./android-roadmap)
- 基线：`c7a28d65`；历史外壳取自 `c61cc064^`
- 状态：P0 证据已收集，历史分发和正式包身份仍待确认；未创建原生 App、安装包或发布。

## 历史包身份与数据

| 项目                        | 仓库证据                                                                                                          |
| --------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| `applicationId` / namespace | `cn.yunyoujun.cook`，旧 `android/app/build.gradle`                                                                |
| 旧显示名                    | 食用手册，旧 `res/values/strings.xml`                                                                             |
| 版本                        | `versionCode = 2`、`versionName = 1.0.1`；这是源码值，不代表已发布最高版本                                        |
| 系统要求                    | 最后源码为 minSdk 24、compile/targetSdk 36                                                                        |
| WebView 身份                | Capacitor hostname 为 `cook.yunyoujun.cn`，Android scheme 为 `https`                                              |
| 登录回调                    | `https://cook.yunyoujun.cn/auth/callback`，不是新原生客户端已经登记或验证的证明                                   |
| 正式签名                    | 旧 app/build.gradle 无 release signingConfig；仓库工作流中未找到 Android 签名/打包步骤                            |
| GitHub Release              | 核对 API 返回的全部 18 条 Release，均无 APK/AAB 附件；beta.9 的说明提到 Android 返回行为，但不能证明 APK 实际分发 |

历史源码可从 [Capacitor 清理提交的父版本](https://github.com/YunYouJun/cook/tree/c61cc064%5E/android) 追溯。没有找到分发证据不等于从未分发，P0 的身份/升级项继续保持未完成。

若曾分发，先取得既有渠道、实际最高 versionCode、安装包的证书 SHA-256 和包身份；凭据通过原有私有签名路径提供，不进入仓库。Android 更新需要证书匹配，换包名会被视为另一应用，见[官方签名说明](https://developer.android.com/studio/publish/app-signing#considerations)。

同包覆盖也不会自动将旧 WebView localStorage/IndexedDB 转成 Room。需用真实旧包确认存储位置及 Schema，再选择可回滚的读取/导出迁移流程，先备份、校验和预览，再原子写入；迁移失败保留旧数据。新原生核心不内嵌 JavaScript 来运行搜索/推荐逻辑。不得清空旧包数据来完成验收。

若维护者确认从未分发，再决定原生版正式包名和首个 versionCode；Debug 使用独立后缀，避免覆盖可能存在的历史安装。正式签名、App Links 与 SSO Registry 在对应里程碑核对。

## 本机可复用环境

本轮仅列出现有安装，未安装新 JDK、SDK、镜像或创建模拟器。

- JDK：Homebrew OpenJDK 21.0.12 可直接运行；系统 `/usr/bin/java` 尚未登记 JDK，构建时显式指定已有 JDK，不修改全局 Java 配置。
- Android SDK：已有 platforms 34、35、36；build-tools 33.0.1、34.0.0、35.0.0。候选工具链要求的 build-tools 36.0.0 尚缺。
- Gradle 缓存：已有 8.14.3 和 9.2.0；不能据此声称新工具链可直接构建。
- 模拟器：已有 `Pixel_8_API_35`、API 35 arm64 Google Play 镜像；本轮未启动或清空。当前没有已连接 Android 设备。
- API 26 与当前稳定系统的最终覆盖仍需安排；现有 API 35 不替代两端验收。

## 工具链候选与约束

候选为 JDK 21、Gradle 9.5.0、AGP 9.3.3、Kotlin 2.4.21，compile/targetSdk 36、build-tools 36.0.0。此处是待实际构建验证的候选，尚未锁定 Version Catalog：

- [AGP 9.3 官方兼容表](https://developer.android.com/build/releases/agp-9-3-0-release-notes?hl=en)要求 Gradle 9.5.0、JDK 至少 17、build-tools 36.0.0，并列出 9.3.3 的修复。
- [Kotlin 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)列出 2.4.21 对 Gradle 7.6.3–9.7.0、AGP 8.5.2–9.3.1 的完整支持范围；9.3.3 超出该表的明确 patch 上限，需以干净构建、核心测试和安装验证消除缺口，不能把表格直接当作成功证据。
- [Gradle Java 兼容表](https://docs.gradle.org/current/userguide/compatibility.html)支持以 JDK 21 运行 Gradle 8.5 及以后版本。App 的 Java/Kotlin 字节码目标与构建 JDK 分别配置。
- AGP 9 已默认内置 Kotlin；App 不重复应用 `org.jetbrains.kotlin.android`。纯 JVM `core` 使用 Kotlin JVM 插件，Compose compiler / serialization 插件与实际解析的 Kotlin 版本一致，见[内置 Kotlin 指南](https://developer.android.com/build/migrate-to-built-in-kotlin)。
- [Navigation 3 稳定版 1.2.1](https://developer.android.com/jetpack/androidx/releases/navigation3)将 compileSdk 要求降回 36；其余 Compose/Material 3 依赖按稳定 BOM 选定后锁定，不使用动态版本。
- API 36 符合[当前 Google Play 新应用要求](https://developer.android.com/google/play/requirements/target-sdk)，但不代表已选 Google Play 为发布渠道。国内渠道要求在分发阶段单独核对。

minSdk 暂按路线图候选 API 26；旧外壳支持 API 24，若存在历史用户，需先确认提高最低版本的影响，不能直接标记设备升级覆盖通过。

## 接下来的实施顺序

1. 补齐历史分发结论，决定正式包身份与迁移分支；核心开发可先推进，正式安装/升级不得跳过该结论。
2. 在独占临时输出目录验证候选工具链。复用现有 JDK/SDK；只补缺少的构建组件，固定 Wrapper 校验值和实际依赖版本，记录解析结果。构建通过后再勾选工具链锁定项。
3. P1 按 `app` / 纯 JVM `core` 两模块落地。共享 JSON 由现有 `pnpm convert` 生成，通过 Gradle 声明输入复制到生成资源；先接数据校验、固定 ID、搜索和推荐 fixtures，再接 Compose 页面与导航。
4. 首个可用版本以离线筛选/搜索/详情、收藏/历史与重启恢复为验收主线；Kitchen、账号、会员同步分别沿 P3/P4 推进。
5. CI 分开核心测试、APK 构建、关键 UI。保留依赖/增量缓存，测试报告归档后回收本轮输出；复用现有设备，记录真实未测范围。

本记录未将规划或环境盘点写作编译、安装、升级或数据迁移通过。
