# 原生 Android 开发方案与分阶段计划

- 日期：2026-10-26
- 状态：技术方向已采纳；原生工程尚未创建
- 决策：[0016 原生 Android 与 Capacitor 外壳退役](../decisions/0016-native-android-and-capacitor-retirement)
- 总计划：[#98](https://github.com/YunYouJun/cook/issues/98)
- Android 待办：[#169](https://github.com/YunYouJun/cook/issues/169)

## 目标与边界

为 Cook 提供独立的原生 Android 客户端，核心做菜流程无需登录即可离线使用。Web 继续保留 Nuxt + Ionic，iOS 继续使用 SwiftUI。三端共享数据、协议和测试用例，各自实现界面及平台能力。

本轮完成旧 Capacitor 外壳清理与方案记录，不启动 Android 功能实现、不发布 Android 安装包。先交付可用的小版本，再逐步对齐 iOS 的厨房工作台、冰箱和私人菜谱能力。

## 技术栈

| 部分        | 选择                                       | 在 Cook 中的用途                                                              |
| ----------- | ------------------------------------------ | ----------------------------------------------------------------------------- |
| 语言 / 构建 | Kotlin、Gradle Kotlin DSL、Version Catalog | 依赖与插件集中锁定稳定版本；Gradle Wrapper、AGP、Kotlin、JDK 按兼容表成套选定 |
| UI          | Jetpack Compose、Material 3                | 原生页面、深浅主题、自适应手机 / 平板布局，保留 Cook 品牌色与图标             |
| 状态        | ViewModel、Coroutines、StateFlow           | UI 发出操作，ViewModel 更新状态，Repository 统一访问数据                      |
| 导航        | Navigation 3                               | 单 Activity、多页面、返回栈与深链                                             |
| 结构化数据  | Room                                       | 收藏、历史、冰箱、私人菜谱及后续同步状态                                      |
| 设置        | DataStore                                  | 主题、常备调料和显示偏好；不用于保存账号令牌                                  |
| HTTP / JSON | Retrofit、OkHttp、kotlinx.serialization    | 数据版本检查、反馈和后续账号 / 同步协议                                       |
| 图片        | Coil                                       | 菜谱配图、头像、加载失败占位与缓存                                            |
| 测试        | JUnit、协程测试、Compose UI 测试           | 核心 fixtures、持久化 / 迁移、关键页面旅程                                    |

初期使用构造函数注入和一个应用级依赖容器，按职责分包；依赖关系明显复杂后再评估 Hilt。涉及稳定版之外的 API 时先证明必要性，不把预览版依赖设为开工前提。

参考：[Compose](https://developer.android.com/compose)、[Material 3](https://developer.android.com/develop/ui/compose/designsystems/material3)、[Android 架构建议](https://developer.android.com/topic/architecture/recommendations)、[Navigation 3](https://developer.android.com/guide/navigation/navigation-3)、[Room](https://developer.android.com/training/data-storage/room)、[DataStore](https://developer.android.com/topic/libraries/architecture/datastore)。

## 工程与数据边界

规划最初只有两个 Gradle 模块，不为每个页面提前拆模块：

```text
apps/android/
  app/                  Compose、ViewModel、Repository、Room、平台集成
  core/                 纯 Kotlin/JVM：类型、搜索、推荐、整句识别
  gradle/libs.versions.toml
packages/data/
  schema/               三端数据结构约束
  fixtures/             三端共享行为用例
```

- `core` 不依赖 Android SDK、Compose 或 Room，可在普通 JVM 上快速测试。
- 构建先执行现有 `pnpm convert`，再由 Gradle 任务复制必要 JSON 到生成的 assets / 测试资源目录；不手工维护第二份菜谱或 fixtures，不复制整个仓库与依赖。
- 内置数据与用户记录分开保存。内置 JSON 受 `schemaVersion` / `dataVersion` 约束，用户数据以本地 Room 为唯一读写来源，远端同步通过 Repository 合并。
- 核心用例覆盖 `recommend`、`search`、`query`、`incompatible`，对应功能实现后接入 `personal-catalog` 和 `private-recipes` 用例。断言内容、稳定菜谱 id、排序、别名及空输入，避免只检查数量。
- 数据更新必须校验 Schema 兼容性并原子替换；失败或离线时继续使用上一份有效数据。不得因更新内置数据而删除收藏与用户菜谱。
- 沿用现有文件格式及其版本边界；收藏 / 历史、私人菜谱、个人食材 / 厨具分别导入导出。现有 JSON 不包含图片与视频附件，不把附件能力算入本期迁移。

## P0：外壳清理与启动前核对

- [x] 删除旧 Capacitor iOS / Android 工程、配置、脚本、直接依赖和 Web 插件调用；保留 Ionic 与云乐坊宿主适配。
- [x] 删除旧外壳 AASA 条目，保留 SwiftUI 的 webcredentials、微信路径及 HTTPS SSO 回调兜底；站点发布单独执行。
- [x] 记录原生 Android 技术方向、阶段与验收条件。
- [ ] 核对旧 Android 是否实际分发、现有包名 / 签名 / 渠道与用户数据迁移入口，再确定新原生 App 的身份和更新策略。
- [ ] 确定首版功能、最低系统版本及目标设备。建议以 Android 8 / API 26 为起点，结合受众和所选依赖的最低要求确认；compile / target SDK 在启动时按稳定工具链与目标渠道要求确定。
- [ ] 锁定工具链版本，确定本地及 CI 构建、模拟器和可回收的产物目录。

验收：Web 测试、类型检查与构建通过；浏览器中 Ionic 页面、主题和授权入口仍可使用；历史用户和包身份核对有书面结果。清理源码完成不代表正式发布或完成用户迁移。

## P1：工程与共享核心

- [ ] 创建 `apps/android` 的 `app` / `core`、Gradle Wrapper、Version Catalog、基础 CI。
- [ ] 接入共享 JSON 与版本检查，移植食材 / 厨具、别名、常备调料、搜索、推荐和整句识别。
- [ ] 运行共享 fixtures，并为 Kotlin / Swift / TypeScript 三端规则变更约定同一验收入口。
- [ ] 建立 Compose 主题、Navigation 3 路由、加载 / 空 / 错误状态与基本无障碍语义。

验收：干净环境可构建并安装 Debug 包；`core` 共享用例通过且顺序一致；断网冷启动可读取内置菜谱。

## P2：本地做菜最小版本

- [ ] 食材与厨具筛选、推荐列表、搜索、菜谱详情、随机菜谱及来源链接。
- [ ] Room 收藏 / 浏览历史、DataStore 设置，重启恢复、保存失败提示与数据库版本迁移。
- [ ] 深浅主题、系统返回、键盘避让与屏幕旋转状态恢复；平板先保证布局可用。
- [ ] 原生反馈、菜谱纠错与投稿入口，沿用统一支持中心及现有反馈协议。

验收：离线完成“选食材 → 找菜 → 看详情 → 收藏 → 重启后找回”；空结果、图片失败、写入失败和返回路径可恢复；关键旅程通过 Compose UI 测试与模拟器走查。

## P3：厨房工作台与文件互通

- [ ] 图文步骤、用量缩放、备料清单、计时器和烹饪进度恢复。
- [ ] 冰箱、到期日与本地临期提醒，按系统权限和电量限制实现可解释的降级；计时提醒与每日临期提醒分别设计。
- [ ] 私人菜谱及个人食材 / 厨具，稳定身份与已有功能的关系遵循共享协议。
- [ ] 通过系统文件选择器导入 / 导出；提供预览、校验、去重、冲突处理和失败时的原子回滚。

验收：Web → Android → iOS → Web 的三种备份协议分别往返；重复导入不重复，失败不损坏原数据；进程重建后厨房进度与计时状态正确。媒体附件由独立需求推进。

## P4：账号与跨平台同步

- [ ] 复用云乐坊身份，通过系统浏览器 / Custom Tabs 和已验证的 HTTPS App Links 完成 SSO；核对生产 Registry，不直接把旧登记视为 Android 已获准使用。
- [ ] 按最终包名和发布证书配置 `assetlinks.json`，验证取消、返回、超时、回调精确匹配、PKCE / nonce、授权码重放、会话恢复与退出。
- [ ] 账号凭据使用受 Android Keystore 保护的专用存储，避免明文放入 DataStore 或备份文件。
- [ ] 明确原生访问 CloudBase 的 HTTP 鉴权与接口合同，先对齐已有收藏 / 历史协议，再单独评估冰箱、私人菜谱等扩展；后端契约不足时补齐接口，不在客户端伪造通道。
- [ ] 本地优先、离线队列、重试、更新时间 / 删除标记合并；退出及账号切换显式保护本机数据，防止不同账号记录混合。
- [ ] 与 iOS 的 CloudBase 同步里程碑协调并单独验收；CloudKit 继续服务 Apple 设备，不被当作 Android 同步后端。

验收：Android / Web 的同账号跨设备读写、离线新增 / 修改 / 删除及账号切换通过；iOS 接入同一后端后才能标记“三端同步通过”。登录成功不替代数据同步或账号隔离验收。

## P5：测试发布与持续交付

- [ ] 启动时锁定的目标渠道内测，完成签名、版本号、升级路径及必要的安装包 / AAB 产物。
- [ ] 完成隐私政策、第三方 SDK 清单、权限说明、统计同意与支持渠道；分发和收费方案确定后再接渠道相关能力。
- [ ] 覆盖最低支持系统和当前稳定系统，验证手机 / 平板、断网、系统返回、进程重建与无障碍。
- [ ] 记录启动、列表滚动、崩溃与 ANR 基线，设置内测反馈与发布退出条件。
- [ ] CI 分开运行核心测试、Android 构建和关键 UI 测试；构建 / 验包使用独占临时目录，保留所需报告后回收可重建产物，复用依赖缓存。

验收：选定渠道可安装、可升级，既有数据保留；验收记录标明版本、设备 / 模拟器与未测范围。内测通过、公开发布和三端功能对齐分别记录，不互相代替。
