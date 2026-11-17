# Cook（云食册）原生 Android 客户端实现方案

- 更新日期：2026-11-17
- 仓库基线：`origin/main`（`41faba4aa`，含 iOS PR #232 及之前全部已合并能力）
- 实现：[原生 Android 工程](https://github.com/YunYouJun/cook/tree/main/apps/android)；本地构建与当前验收范围见其 README。
- 协议规范：[android-protocol-spec.md](./android-protocol-spec.md)
- 历史参考：[android-roadmap.md](./android-roadmap.md)、[android-startup-2026-11-06.md](./android-startup-2026-11-06.md)

---

## 1. 基线核对与前提修正

### 1.1 代码库实际规模（`origin/main = 41faba4aa`）

- **`apps/ios/Cook`**：101 个 Swift 文件（含 4-Tab 主界面、发现场景、厨房工作台、冰箱分类与拍照识别、私人菜谱与视频、账号/会员/同步/AI 服务）。
- **`apps/ios/CookCore`**：**23 个 Swift 源文件、19 个测试文件**（覆盖核心推荐/搜索/整句/相克、饮食目标与机构低 GI、用量注释/缩放/步骤用量计划、正文/展示模型、AI 查找意图与分步指导、个人词典、私人菜谱与视频关联、跨端用户数据文档与会员 Token/购买契约）。
- **需纠正的早期误判**：
  1. iOS `RecipeDataSettingsView` 目前**仅展示内置数据版本、条数和来源**，并不存在「在线菜谱检查与热更新」功能；在线更新归类为 **Android 新增 / 延期能力**，不放进首个离线版本。
  2. iOS `KitchenWorkbench` **按菜谱保存单个计时器**；「每步多个独立计时器」属于增强需求。
  3. 当前 `Search` 实现**不包含拼音搜索契约**，只做名称、食材、标签、别名匹配。
  4. `user-data-sync-boundaries.json` 实际包含**墓碑配额同时间竞争**用例；256 KiB 上限、非法字段、空文档保护等在 `UserDataDocumentTests.swift` 中，因此测试映射必须同时覆盖共享 fixtures 与 Swift 测试用例。
  5. Android 系统版本号纠偏：API 24 对应 Android 7，Android 6 是 API 23；当前候选 `minSdk = 26`（Android 8.0），发布测试需覆盖至 **Android 17 / API 37**（已稳定发布）。

### 1.2 本机工具链与前置环境

- **JDK**：Homebrew OpenJDK `21.0.12`（仅作为 Gradle/构建 JDK；`app`/`core` Java & Kotlin 字节码目标显式锁定为 **JVM 17**，避免误依赖 Java 21 API）。
- **Android SDK**：已装 `platforms/android-34`、`35`、`36`，`build-tools/33.0.1`、`34.0.0`、`35.0.0`；`Pixel_8_API_35` arm64 模拟器；本地缓存已有 AGP `8.13.0`、Gradle `8.14.3` / `9.2.0`、Kotlin `2.2.20` / `2.3.0`。
- **包名与发版确定项**：
  - 正式 `applicationId` 已拍板统一为 `fun.yunle.cook`（对齐 iOS Bundle ID，Debug 构建固定加 `.debug` 后缀：`fun.yunle.cook.debug`）。
  - **包名解耦原则**：Kotlin 源码包名固定为 `cn.yunyoujun.cook`（避开 Kotlin 关键字 `fun` 转义），与 `build.gradle.kts` 中的 `applicationId` 解耦。
  - 若未来需接管旧外壳 `cn.yunyoujun.cook`（`versionCode = 2`），发布前仍需确认历史分发范围、发布签名证书与最高 `versionCode`。

---

## 2. iOS → Android 全量功能对齐矩阵（五类状态）

状态分类：**有对应实现**（不代表整阶段验收通过）、**构建开关关闭**（实现且受 Feature Flag / 服务端登记控制）、**平台替代**（原生等价实现并写明边界）、**延期**（独立增量，不阻塞首版）、**Android 新增**（超出当前 iOS 范围）。

| 功能域                   | iOS 模块 (`origin/main`)                                                            | Android 对齐实现                                                                                                                                                                                                      | 对齐状态                | 目标阶段                |
| ------------------------ | ----------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------- | ----------------------- |
| **共享核心算法**         | `CookCore`（推荐/搜索/整句/相克/归一化）                                            | `:core` 纯 Kotlin/JVM 模块                                                                                                                                                                                            | 有对应实现              | P1b                     |
| **菜谱正文与展示**       | `RecipeText` + `RecipePresentation` + `RecipeImageViewer`                           | `:core` 块级模型（章节/列表起始号/来源许可/未知块回退）+ Compose 详情与图片缩放/分享/重试                                                                                                                             | 有对应实现              | P2                      |
| **完整用量链**           | `RecipeAmounts` + `Annotations` + `InstructionAmounts` + `StepUsagePlan`            | 1–20 份缩放、固定批次不倍乘、引用/取整/分数/范围/条件量、基于「出现次数+原文」的步骤 ID、失效整菜回退原文                                                                                                             | 有对应实现              | P2 / P3a                |
| **找菜与会话状态**       | `CookSession` + `CookDiscovery`（5 场景）+ `AvoidPicker` + `PantryPicker`           | 明确区分「清空本次条件 / 清除筛选 / 取消默认目标」，支持无食材浏览候选池、菜名/食材句式区分、去重换一批                                                                                                               | 有对应实现              | P2                      |
| **饮食目标与机构低 GI**  | `DietaryGoals`（3 目标）+ `InstitutionalLowGI`（独立发现场景）                      | 严格校验 3 目标（每目标≥10、总≥30）与原始字节 SHA-256 / UTF-8 NUL FNV-1a 指纹；机构低 GI 独立校验发布者/URL/参考资料，不混为第 4 个目标                                                                               | 有对应实现              | P2                      |
| **随机决定与摇一摇**     | `CookDecideSheet` + `DeviceShake`                                                   | 随机弹窗（遵守当前全部约束）+ `SensorManager` 摇一摇（无加速度计设备提供显式按钮入口）                                                                                                                                | 有对应实现              | P2                      |
| **本地库与设置持久化**   | SwiftData（`Favorite`, `HistoryEntry`, `Preferences`）+ `UserDefaults`              | Room 显式分区 `DeviceLibrary` 与 `AccountLibrary(uid)` + SharedPreferences；支持保存失败弹窗与重试                                                                                                                    | 有对应实现              | P1b / P2                |
| **反馈与匿名信号**       | `FeedbackClient` + `SignalConsentView`（支持投稿/回执/工单链接/支持中心/邮件/复制） | 对接 `cook-feedback`（已在 `cloudfunctions/cook-feedback/index.js` 放行 `platform = "android"`，待独立部署云函数后联调）；信号同意与微信 SDK 同意分离                                                                 | 有对应实现              | P2                      |
| **厨房工作台与计时**     | `KitchenWorkbench` + `KitchenTimerView`                                             | 按菜谱恢复备料/步骤/份数/计时；修改份数仅取消用量变化项的勾选；`AlarmManager` 持久化截止时间 + `canScheduleExactAlarms` 降级提示                                                                                      | 平台替代                | P3a                     |
| **AI 分步指导**          | `StepGuidance` + `ManagedStepGuidanceService` (`cook-ai-guidance`) + DeepSeek BYOK  | 同时支持**会员托管接口**与 **BYOK**；绑定菜谱/数据版本/指纹/步骤 ID/份数/requestId/contextRevision，当前步必引、数字单位全校验、取消/revision 隔离、不落库                                                            | 有对应实现              | P3a（BYOK）/ P4（托管） |
| **冰箱与分类库存**       | `Fridge` + `FridgeCategory` + `FridgeIntake` + `FridgeReminder`                     | 自定义分类增改删（删分类自动归类）、同食材多批库存、区分包装/推算/食用建议/手动日期；AlarmManager 非精确周期检查，按当地日期去重每日至多 1 条临期通知（允许 Doze 延迟）                                               | 平台替代                | P3b                     |
| **冰箱拍照识别**         | `FridgePhotoSheet` + `FridgePhotoRecognition` (Vision OCR + 蔬果分类)               | 系统图片选择 + Bundled ML Kit 中文 OCR (`text-recognition-chinese`)；CameraX 和端侧图像分类待后续接入；保留 EXIF 旋转、30MB 上限、冲突日期不自动采用，照片与 OCR 原文不落库                                           | 平台替代                | P3b                     |
| **私人菜谱与个人词典**   | `PrivateRecipes` + `PrivateRecipeVideo` + `PersonalCatalog`                         | 支持内置条目隐藏/恢复、隐藏后仍识别既有名称；私人菜谱 `customId`、墓碑防复活、v1 导入不删 v2 视频关联、系统 VideoView 播放本地拷贝视频（不兼容编码给出提示，不解析 iCloud 引用）                                      | 平台替代                | P3c                     |
| **三大 JSON 文件互通**   | `UserDataDocument` / `PersonalCatalog` / `PrivateRecipes`                           | SAF 系统选择器导入导出、严格区分「文件导入（保留并集/只增或按文件策略）」与「云同步合并（删除传播）」、失败原子回滚                                                                                                   | 有对应实现              | P3c                     |
| **云乐坊 SSO 与微信**    | `YunlefunSSO` (`#ylf_sso=` v3) + `WeChatAuthorization` + `WeChatSignup`             | 系统浏览器 + HTTPS 回调 + 10 分钟 PKCE/nonce 和 Tink AES-GCM；App Links 与服务端登记仍待验收，微信 SDK 尚未接入                                                                                                       | 构建开关关闭 / 平台替代 | P4                      |
| **CloudBase 跨平台同步** | `AccountUserDataStore` + `UserDataSyncService` (v2)                                 | 必须有效会员 + **显式启用**；首启不自动上传设备库，通过「复制到当前账号」显式迁移；完整 `UserDataDocument` v1 / `protocolVersion = 2` + `ownerDigest` + 请求后二次本地合并；退出/关闭同步恢复设备库并保留账号离线缓存 | 有对应实现              | P4                      |
| **AI 智能找菜**          | `SearchIntent` + `ManagedCookAIService` + `DeepSeekIntentService`                   | 生成可编辑条件草稿，用户确认才应用；失败不自动写长期偏好；凭据 HTTP 客户端与图片/浏览器完全隔离（无全局拦截器、禁重定向/Cookie/缓存、禁自动重试）                                                                     | 有对应实现              | P4                      |
| **会员查询与支付**       | `MembershipService` + `MembershipPurchase` (StoreKit 2)                             | **权益读取先行**：对齐 `MembershipAccountToken` 与云乐坊会员状态查询；StoreKit 2 编排**不移植**，Android 端内购/外部购按地区与渠道合规审核后通过独立 Flag 开启                                                        | 构建开关关闭 / 延期     | P4 / P5                 |
| **云厨房 3D**            | `CloudKitchen` (USDZ)                                                               | 阶段性延期：P1–P4 不阻塞；P5 评估 glTF + Filament/SceneView 或轻量化 2D 厨台                                                                                                                                          | 延期                    | P5                      |
| **在线数据检查与热更新** | _(iOS 仅展示内置信息，无热更新)_                                                    | 独立增量需求：若做须校验 `schemaVersion`、`dataVersion`、原始字节指纹并原子切换                                                                                                                                       | Android 新增 / 延期     | P5（可选）              |
| **单菜多步独立计时器**   | _(iOS 按菜谱保存单计时器)_                                                          | 在对齐单计时器基础上，作为后续增量扩展                                                                                                                                                                                | Android 新增 / 延期     | P5（可选）              |

---

## 3. 工程结构、工具链与安全基线

### 3.1 Gradle 双模块结构

```text
apps/android/
  build.gradle.kts
  settings.gradle.kts
  gradle.properties
  gradle/
    libs.versions.toml              # 锁定精确版本（禁用 + 或动态版本）
    wrapper/
  core/                             # 纯 Kotlin/JVM（jvmTarget = 17，零 Android 依赖）
    src/main/kotlin/cn/yunyoujun/cook/core/...
    src/test/kotlin/cn/yunyoujun/cook/core/...
  app/                              # Compose + M3 + Navigation 3 + Room + SharedPreferences + Tink
    schemas/                        # Room 导出 schema JSON（入库用于迁移回归）
    src/main/kotlin/cn/yunyoujun/cook/...
      di/                           # AppContainer（手动构造函数注入，不用 Hilt）
      data/local/                   # DeviceLibrary / AccountLibrary Room DB、DAO、设备设置、Tink Vault
      data/remote/                  # 独立凭据隔离客户端（Feedback、SSO、CloudBase、ManagedAI、DeepSeek）
      data/repository/              # 不可变内置资产快照 + 内存索引 + 用户库仓库
      platform/                     # AlarmManager 计时调度与冰箱去重通知
      ui/                           # 4-Tab + Navigation 3 NavKey + Compose 页面与 Adaptive 布局
```

### 3.2 工具链验证策略（P1a 必须用真实链通过）

- **主选或兼容回退双轨**：
  - 优先验证当前本地可用且稳定兼容的工具链（如 `AGP 8.13.x` + `Gradle 8.14.3` + `Kotlin 2.3.x` + `KSP2` + `build-tools 35.0.0`，或补充 `build-tools 36.0.0` 后验证 `AGP 9.3.3` + `Gradle 9.5.0` + `Kotlin 2.4.21`）。
  - 无论选择哪套，必须在 **P1a 构建验证工程** 中同时包含：`:core` 依赖 + Compose + `kotlinx.serialization` + **Navigation 3**（可序列化 `NavKey` 状态恢复）+ **真实 Room + KSP2 + Schema 导出与迁移测试** + **Debug APK** + **R8 Release 构建**，并在首次联网解析后跑通 `--offline` 缓存重建。
- **关键依赖定型**：
  - **加密存储**：禁用已弃用的 `androidx.security:security-crypto`，明确采用 **`com.google.crypto.tink:tink-android`**（API 24+ 支持，与 `minSdk = 26` 兼容）配合 Android Keystore 管理 AEAD 密钥，密文原子写入且显式从 `backup_rules.xml` / `data_extraction_rules.xml` 排除；密钥失效或损坏时绝不降级明文、绝不清空菜谱库。
  - **OCR**：默认使用 **Bundled 中文模型** `com.google.mlkit:text-recognition-chinese:16.0.1`，不依赖 GMS 动态下发模型。
  - **媒体与相机**：CameraX 使用修复 Android 17 兼容的 `1.5.2+` / `1.6.0+`；Media3 统一版本（如 `1.11.1`），并审查所有 native `.so` 的 **16 KB 页面对齐**。
  - **安全网络客户端**：`CloudBase` 与 `DeepSeek` 通过独立的 `HttpURLConnection` 请求（禁重定向、关闭缓存、限制最大响应字节，绑定请求取消与 `contextRevision` / `sessionRevision`），绝不通过全局拦截器挂载 Bearer/API Key。

---

## 4. 资产流水线与全量测试映射

### 4.1 Gradle 资产白名单与原始字节校验

Gradle `syncCookAssets` 任务必须声明精确的输入（`packages/data/`、`packages/shared/links.json`、`packages/shared/Assets.xcassets/`）与输出目录，仅做增量构建，**严禁只检查单个文件存在**，并保证 JSON **原始字节拷贝（不重新格式化）**：

- **必需 JSON**：`recipe-meta.json`、`incompatible-foods.json`、`aliases.json`、`food.json`、`pantry.json`、`query.json`、`shelf-life.json`、`recipe-text.json`、`recipe-amounts.json`、以及按 `recipe-meta.json` 的 `chunked` 字段检验 `recipe.json` 或全部 `recipe-chunk-*.json`。
- **可选扩展 JSON**：`recipe-dietary.json`、`recipe-institutional-low-gi.json`（缺失时允许启动，失效时按各自模块规则关闭对应入口而不是拖垮全 App）。
- **指纹一致性**：`:core` 对 `recipe-amounts.json` 原始字节做 SHA-256，FNV-1a 64 位正文指纹严格复刻 UTF-8 字节、`\0` 分隔与无符号 64 位溢出语义。

### 4.2 测试映射（Fixtures + Swift 用例补齐）

1. **9 组共享 Fixtures**：`recommend.json`、`search.json`、`query.json`、`incompatible.json`、`personal-catalog.json`、`private-recipes.json`、`step-guidance-numbers.json`、`user-data-sync.json`、`user-data-sync-boundaries.json`（均校验输出字段、稳定 ID、排序与空输入）。
2. **补齐仅在 `CookCoreTests` 中的 Swift 契约用例**：
   - `DietaryGoalsTests`（v1/v2 解码、阈值 10/30、指纹校验、完整食材+复合原料忌口、原单位保留）
   - `InstitutionalLowGITests`（来源核验、10 条门槛、非第四目标边界）
   - `RecipeAmountsTests` / `RecipeAmountAllocationTests` / `RecipeAmountAnnotationsTests` / `RecipeInstructionAmountsTests` / `RecipeStepUsagePlanTests`（1–20 缩放、固定批次、分次不确定阻断、步骤 ID 稳定性）
   - `RecipePresentationTests` / `RecipeTextTests`（章节、列表起始编号、未知块回退）
   - `SearchIntentTests` / `ManagedIntentContractTests`（strict 解码拒绝未知字段、版本与数据指纹一致、食材/忌口冲突校验）
   - `StepGuidanceTests`（当前步必引、数字表达式与单位匹配）
   - `UserDataDocumentTests`（256 KiB 体积上限、非法字段/负时间拒绝、删除优先、200/500/180 天裁剪）
3. **CI 触发条件**：监听 `apps/android/**`、`packages/data/**`、`packages/cook/**`、`packages/shared/**`、`cloudfunctions/cook-feedback/**`、`scripts/**`、根锁文件与 workflow 文件。

---

## 5. 细化后的分阶段路线（P0 → P5）

| 阶段                                 | 核心范围                                                                                                                                                                      | 明确退出 / 验收标准                                                                                                                                              |
| ------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **P0 + P1a（基线与构建验证）**       | 隔离工作树、协议规范文档 [android-protocol-spec.md](./android-protocol-spec.md)、Gradle/AGP/Kotlin/Compose/Nav3/Room+KSP2/R8 真实链验证、资产白名单拷贝任务                   | `:core:test`、`:app:assembleDebug`、`:app:assembleRelease`（含 R8）、`--offline` 二次构建全部通过；Room 导出 schema；记录真实解析版本并锁定 `libs.versions.toml` |
| **P1b（离线核心与骨架）**            | 移植核心数据加载、归一、推荐、搜索、整句、相克、用量/正文/饮食目标基础模型；Navigation 3 四 Tab 状态保存、Room 双域建库（`DeviceLibrary` / `AccountLibrary`）、冷启动离线加载 | 9 组 fixtures 与对应核心单元测试全绿；断网安装冷启动可在 4-Tab 间切换并恢复状态                                                                                  |
| **P2（可交付离线版本）**             | 找菜全流程、饮食目标与机构低 GI、随机/摇一摇、菜谱正文/图片缩放/份数缩放、收藏与历史（含失败重试弹窗）、基础设置；`cook-feedback` 放行 `platform=android` 并接入客户端回执    | 离线完成端到端找菜-详情-收藏-杀进程恢复；Compose UI 测试 + `Pixel_8_API_35` 走查通过；`cook-feedback` 带回执联调通过                                             |
| **P3a（厨房工作台与安全 Vault）**    | 工作台备料/步骤/份数联动勾选、Tink Keystore Vault、隔离凭据客户端、DeepSeek BYOK 分步指导、AlarmManager 倒计时与权限降级状态机                                                | 旋转/进程重建/修改系统时间/撤回精确闹钟权限测试通过；Vault 密文与失败保护测试通过                                                                                |
| **P3b（冰箱与拍照识别）**            | 冰箱分类管理、多批库存、保质期与建议区分、每日去重提醒、照片选择与端侧中文 OCR；系统相机入口和图像分类待后续                                                                  | 冲突日期不自动采用、照片/原文不落库、拒绝相机与通知权限均可完成录入与降级提示                                                                                    |
| **P3c（私人菜谱、词典与文件互通）**  | 个人词典隐藏/恢复、私人菜谱 CRUD 与删除防复活、SAF 三大 JSON 往返导入导出（预览/去重/原子回滚）、本地视频附件播放与不兼容提示                                                 | Web ↔ Android ↔ iOS 三端文件互导往返通过；v1 导入不误删 v2 视频关联；坏文件/磁盘满保持原数据                                                                     |
| **P4（账号、同步、托管 AI 与会员）** | 云乐坊 SSO v3 (`#ylf_sso=`) + App Links、微信登录（Flag 控制）、CloudBase 同步（显式开启+复制迁移+二次合并）、托管找菜与托管指导、会员权益读取                                | 登录/同步/账号切换请求竞态测试全绿；离线编辑重连双向同步通过                                                                                                     |
| **P5（大屏、无障碍、合规与发布）**   | 平板自适应双栏、TalkBack/字体缩放/Predictive Back、API 26–37 兼容与 16 KB 页面检查、正式签名与内测发布、可选评估云厨房 2D/3D 与在线数据更新                                   | 最低/最高系统真机回归与性能基线记录完成；隐私清单与渠道规则审核通过                                                                                              |
