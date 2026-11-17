# iOS 做菜足迹与「今天学会了」

对应 [Issue #235](https://github.com/YunYouJun/cook/issues/235)。首版仅记录本机做过次数和首次学会日期，不包含照片、社交分享、连续打卡、等级或复杂徽章。

## 复用的模块

- 官方菜谱：`CookCore/Models.swift` 的 `Recipe.id: Int`，ID 稳定且不复用。
- 私人菜谱：`PrivateRecipe.customId: String`，通过 `PrivateRecipeRecord` / `PrivateRecipeSnapshot` 读取；删除后的足迹保留菜名快照或占位，允许删除记录。
- 本机记录：沿用应用级 `CookedRecipeRecords` 的 `@Observable` 实例和 `UserDefaults`，由 `CookApp` 注入。足迹不加入 SwiftData / CloudKit 或云乐坊账号同步。
- 页面：`RecipeDetailView`、`PrivateRecipeDetailView`、`KitchenWorkbenchView` 和「我的」`SettingsView`。

## 存储与迁移

旧键 `cook.cookedRecipes.v1` 是 `[String: Date]`，使用 `catalog:<Int>` / `personal:<String>` 区分来源，每道菜只有一个做过日期。

首次创建 Store 时，如果不存在 `cook.cookingProgress.v2`，把旧表中的每个日期原样转换为一条 `CookedRecord`。不把日期改为迁移当天，不推断学会状态，也不捏造早期重复下厨次数。新旧 ID 命名空间一一对应，官方与私人菜谱不会串记录。

v2 将版本号、全部 `CookedRecord` 和 `LearnedRecord` 编码为一个 JSON `Data` 存入本机 `UserDefaults`。即使迁移结果为空，也写入 v2 快照；之后只读写 v2，撤销到零条后不会从 v1 复活。v1 原表保留作恢复底本，不再双写。v2 数据损坏或版本过新时保留原文并阻止覆盖，页面提示无法读取。

每条记录使用独立 UUID。新操作保存当时的菜名，详情与时间线优先显示当前菜名；关联菜谱删除时使用快照或占位。旧记录没有菜名快照，仍可通过原 ID 解析现有菜谱。

## 状态转换

| 用户操作                                       | 做过                                           | 学会                     | 庆祝           |
| ---------------------------------------------- | ---------------------------------------------- | ------------------------ | -------------- |
| 浏览、步骤勾选、计时结束、收起工作台、跳过收尾 | 不变                                           | 不变                     | 无             |
| 我做过 / 今天又做了一次 / 只记为做过           | 当天无记录时新增；同日重复轻提示               | 不变                     | 无             |
| 记为学会 / 今天学会了                          | 当天无记录时补一条 `createdFromLearned = true` | 未学会时记录 `learnedAt` | 仅本次成功确认 |
| 删除单次或当天做过记录                         | 只删指定 UUID                                  | 不变                     | 无             |
| 普通撤销学会                                   | 全部保留                                       | 移除标记，可重新主动确认 | 无             |
| 庆祝卡片「点错了？撤销」                       | 只撤回本次自动生成的 UUID，保留既有记录        | 撤回本次标记             | 关闭           |

单日防重通过调用时的 `Calendar.current.isDate(_:inSameDayAs:)` 判断自然日，不用固定 24 小时窗口。跨午夜允许再记一次，夏令时切换也按当地自然日处理。

`LearningConfirmation` 保存本次学会 UUID、自动补写的做过 UUID 和累计学会数。撤销会先核对学会 UUID；重复撤销或过期卡片不能撤回之后重新确认的状态，也不能误删同日重建的另一条做过记录。

全部时间线按日期倒序分组；首次学会当天的做过记录附「✨ 学会了」。删除该条做过后，学会里程碑单独保留。「已学会」筛选始终每道菜显示一个首次学会条目；可通过详情菜单或时间线操作撤销学会。

## 页面与无障碍

详情采用渐进式入口：初始只显示「我做过这道」；做过后显示次数、记录菜单和「记为学会」；学会后显示日期铭牌和必要时的「今天又做了一次」。铭牌菜单只提供查看足迹和撤销学会。

「完成做菜」位于工作台做法正文末尾，无图文做法时也提供按钮。收尾 Sheet 支持下滑和跳过。主动确认学会后展示庆祝卡片、系统成功触感、轻弹簧和约 1.2 秒星光。系统开启 Reduce Motion 时不生成粒子、不做位移或缩放，只保留淡入、文字与触感。支持空白处收起、完成按钮、即时撤销和大字体滚动。

## 验证入口

```sh
swift test --package-path apps/ios/CookCore
xcodegen generate --spec apps/ios/project.yml
xcodebuild test -project apps/ios/Cook.xcodeproj -scheme Cook \
  -destination 'platform=iOS Simulator,id=<existing-shared-udid>' \
  -parallel-testing-enabled NO -collect-test-diagnostics never \
  -only-testing:CookTests \
  -only-testing:CookUITests/CookingFootprintUITests \
  CODE_SIGNING_ALLOWED=NO
```

纯状态测试覆盖自然日防重、跨日、夏令时、来源隔离、显式学会、禁止重复庆祝、两种撤销、过期撤销令牌、时间线里程碑保留及编码往返。Store 测试覆盖历史日期迁移、持久化、撤销后的空快照及损坏／未来版本保护。UI 测试覆盖渐进式入口、收尾关闭与跳过、庆祝即时撤销、已学会收尾、单条删除和私人菜谱删除后的降级展示。

### 2026-11-18 验证结果

以下结果来自以 `c140e30c5` 为基线、只包含本功能的独立工作树：

- `swift build --package-path apps/ios/CookCore` 通过。
- CookCore 全量 138 项：137 通过，1 项实时 Web 编码桥接测试按原设计跳过；其中新增状态机测试 8 项全部通过。
- Xcode Debug 模拟器测试构建通过。固定复用共享 iPhone 18 Pro、iOS 27.0、UDID `4502D5E9-9630-4AF0-9050-3C9109139FD0`，关闭并行测试，未新建模拟器。
- App 全量 `CookTests` 304 项全部通过，包含 4 项 Store 测试及隐私声明检查。临期提醒的旧措辞断言已在基线修复；本功能另外验证主动记录、删除／撤销和不参与云同步／AI 请求的足迹说明。
- `CookingFootprintUITests` 3 项在同一轮测试中全部通过，覆盖详情／足迹状态转换、工作台关闭／跳过／即时撤销，以及私人菜谱删除降级。Xcode 合计 307 项、0 失败。
- 本功能无新增编译警告。基线已有警告位于 `YunlefunAccountSection.swift:195` 的 Safari 委托隔离及 `DietaryGoalAppearance.swift:28` 的旧 trait API；测试构建还会输出现有 StoreKit SDK 弃用及 App Intents 元数据提示。

截图、测试摘要与回收记录保存在本地忽略目录 `artifacts/ios-cooking-footprint-2026-11-18/`，独立工作树的验证证据位于其 `pr-verification/` 子目录。验证不包含真实设备触感体验；Reduce Motion 的分支由系统环境值控制。

## 本次文件清单

下表列出本功能及相关隐私测试的 18 个文件。路径相对仓库根目录。

| 文件                                                               | 类型 | 用途                                     |
| ------------------------------------------------------------------ | ---- | ---------------------------------------- |
| `apps/ios/CookCore/Sources/CookCore/CookingProgress.swift`         | 新增 | ID、记录、状态转换与时间线投影           |
| `apps/ios/Cook/Model/CookedRecipeRecords.swift`                    | 修改 | 本机 v2 存储、历史迁移和写入保护         |
| `apps/ios/Cook/Views/CookedRecipeRecordRow.swift`                  | 新增 | 两类菜谱共用的渐进式进度入口             |
| `apps/ios/Cook/Views/CookingCelebrationView.swift`                 | 新增 | 庆祝卡片、触感、Reduce Motion 和日期文案 |
| `apps/ios/Cook/Views/CookingFootprintView.swift`                   | 新增 | 我的入口、统计、筛选、时间线与单条删除   |
| `apps/ios/Cook/Views/KitchenCompletionSheet.swift`                 | 新增 | 可跳过的工作台收尾                       |
| `apps/ios/Cook/Views/RecipeDetailView.swift`                       | 修改 | 官方菜谱接入进度行                       |
| `apps/ios/Cook/Views/PrivateRecipesView.swift`                     | 修改 | 私人菜谱接入进度行及足迹回跳             |
| `apps/ios/Cook/Views/KitchenWorkbenchView.swift`                   | 修改 | 显式收尾入口与轻提示                     |
| `apps/ios/Cook/Views/RecipeTextView.swift`                         | 修改 | 仅工作台使用的正文末尾完成按钮           |
| `apps/ios/Cook/Views/SettingsView.swift`                           | 修改 | 「我的」足迹入口                         |
| `apps/ios/CookCore/Tests/CookCoreTests/CookingProgressTests.swift` | 新增 | 核心状态机测试                           |
| `apps/ios/CookTests/CookedRecipeRecordsTests.swift`                | 修改 | Store 迁移和持久化测试                   |
| `apps/ios/CookTests/FavoriteSaveTests.swift`                       | 修改 | 本机足迹隐私声明回归测试                 |
| `apps/ios/CookUITests/CookingFootprintUITests.swift`               | 新增 | 完整交互回归测试                         |
| `apps/ios/README.md`                                               | 修改 | 功能说明与开发文档入口                   |
| `docs/privacy.md`                                                  | 修改 | 本机足迹数据说明                         |
| `docs/dev/ios-cooking-footprint.md`                                | 新增 | 本文，设计、迁移、验证和文件清单         |
