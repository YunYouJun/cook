# 云食册 iOS App

SwiftUI 原生 App，计划和验收标准见 [iOS App 打磨目标](../../docs/dev/ios-roadmap.md)。

菜谱详情中的「厨房工作台」提供图文做法、备料清单和计时器；宽屏并排展示，窄屏分段切换，可选择上下排列的「台面」布局。使用新 SDK 构建时支持 iOS 27.1 的系统折叠分区。交互、保存规则及验证范围见 [厨房工作台](../../docs/dev/ios-kitchen-workbench.md)。

TestFlight 发布参数、正式反馈配置和内部组验收见 [原生发布流程](../../docs/dev/ios-testflight.md)。

- `CookCore/`：推荐、搜索、相克判断，移植自 `packages/cook`，和 Web 跑同一份用例。
- `Cook/`：App 界面（SwiftUI）、收藏和浏览记录（SwiftData，有 iCloud 权限时通过 CloudKit 同步）。
- `project.yml`：XcodeGen 工程定义，生成的 `Cook.xcodeproj`、`Info.plist`、`Cook.entitlements` 不提交。

## 本地运行

需要 Xcode 26 和 [XcodeGen](https://github.com/yonaskolb/XcodeGen)（`brew install xcodegen`）。

```bash
# 在仓库根目录
pnpm install
pnpm convert                                  # 生成菜谱 JSON，构建时会拷进 App
xcodegen generate --spec apps/ios/project.yml
open apps/ios/Cook.xcodeproj
```

真机调试和上传 TestFlight 需要先 `export APPLE_DEVELOPMENT_TEAM=你的 Team ID` 再运行 `xcodegen`，并在开发者后台为 `fun.yunle.cook` 开启 iCloud（容器 `iCloud.fun.yunle.cook`）。

```bash
swift test --package-path apps/ios/CookCore   # 只跑核心逻辑
```

CI 的 `ios` job 在 iPhone 和 iPad 模拟器上先后跑单元测试和 UI 测试。失败时 `Show test failures` 一步会写出失败原因，完整结果包（截图、每一步的操作记录）在这次运行的 Artifacts 里，名为 `ios-xcresult`，下载后用 Xcode 打开。

本地可运行 `pnpm exec tsx scripts/select-ios-test-devices.ts` 选择当前 Xcode 能使用的现有 iPhone／iPad；脚本按实际设备类型识别机型，也能复用自定义名称的设备，并优先保留已验证的 13 英寸 M5 iPad 覆盖。后续按输出的 UDID 运行，常规测试显式设置 `-parallel-testing-enabled NO`。脚本不会创建或清空设备；仅在共享池缺少必要覆盖时新建。需要全新应用状态或隔离时，使用测试数据隔离、其他兼容现有设备或串行排队，不另建同系统同机型副本。

按维护者 2026-10-28 的决定，本机各项目共用模拟器，相同系统版本和机型仅保留一台；共享清单及旧 UDID 替代映射位于 `~/Library/Developer/SimulatorMaintenance/shared-devices.json`。当前本地覆盖 iOS 26.5／27.0／27.1，不再将 iOS 17／18 的运行验收作为本地门禁，也不为历史待办恢复低版本运行时。已有测试结果按原始通过／失败状态保留；此调整不修改 App 的编译部署目标。

本地遇到 `launchd_sim could not bind to session` 或 Xcode 等待模拟器启动 60 秒后超时，先用 `xcrun simctl list devices available` 确定目标 UDID，再运行 `python3 apps/ios/scripts/prepare-simulator.py <UDID>`。脚本仅准备指定设备：检查对应运行时的共享缓存、等待完整启动、确认 SpringBoard 可响应，总超时默认 360 秒；成功后再向同一个 UDID 执行测试。它不清空设备、不重启全局服务。缓存检查依据 [Apple 开发者工程师的排查说明](https://developer.apple.com/forums/thread/807025)。

本地使用 Xcode 27.1 / iPadOS 27.0 时，如果中文输入后的每次操作都报告 `App animations complete notification not received` 并等待约 60 秒，可在模拟器键盘的地球菜单中切换到 English (US) 后重跑。2026-10-24 的验收中，仅切换输入法即消除了该等待，测试仍输入和验证中文菜名、食材及厨具。不要通过跳过断言、禁用应用动画或无限增加超时来判定通过。iPad 的内联搜索框没有 iPhone 的取消按钮，测试需提交并清除搜索后再定位系统返回按钮。

## 菜谱数据

「我的 → 关于云食册 → 数据版本」进入「菜谱数据」页，展示当前内置菜谱数量与可复制的版本标识，复制后显示确认状态。页面说明数据随 App 内置，并提供菜谱与食材数据仓库、实际内置图文做法的来源链接；外部项目展示的当前内容可能与 App 内置版本不同。版本标识来自 `recipe-meta.json` 的 `dataVersion`，不是 Git 提交号；页面不把生成时间作为发布日期，也不展示在线更新状态。

详情页保留系统分组列表，来源入口复用设置页的语义图标与副标题，整行可点击并标注外链方向。复制操作每次均触发系统触觉反馈，并通过 VoiceOver 播报结果；浅色、深色和最大辅助字体分别提供预览。

## 饮食目标

首次进入做菜页时，以可跳过的轻量引导介绍“减少添加糖”或“均衡搭配”。选择、完成或点击“暂不设置”后，引导在这台设备上收起；已记住目标的用户不再重复引导。之后从“筛选 → 饮食目标”修改，随机页与结果页提供“更多 → 饮食目标”入口；未设置目标时不常驻占一行，已启用目标保留紧凑提示。

可以先按目标浏览已核对做法，再叠加食材、厨具、忌口和其他筛选；经典列表、云厨房、随机及补食材建议共用当前候选池。目标结果展示具体理由，优先打开同包内的已核对图文，也支持进入原生厨房工作台。

首次选择及之后修改目标都会自动保存在独立本机设置，下次启动继续使用，无需额外开启记忆开关；不进入 SwiftData 或 iCloud。清空本餐不修改已保存的默认目标；明确选择“不限目标”会取消保存。目标推荐不会写入现有不含目标条件的排序评测信号。

首批标注覆盖 32 道菜，其中 12 道支持“减少添加糖”，32 道支持食物类别搭配提示。“控制体重”尚未达到每目标 10 道的来源证据门槛，入口保持关闭。目标不是营养成分认证；阅读页说明各目标的核对范围，并隐藏未纳入本次核对的预估热量指标卡片。

标注源为 `packages/data/recipe-dietary.json`，运行 `pnpm dietary:validate` 检查 schema、来源和覆盖。CookCore 核对配方字段、图文指纹与用量文件 SHA-256 后才采用标注；数据缺失、过期或不兼容时继续支持普通推荐。实施范围、审核记录和后续跨端计划见 [饮食目标计划](../../specs/dietary-goals/README.md)。

目标弹窗由页面的稳定父视图持有，从结果区取消目标后仍可点击完成。目标列表在字号变化时重算行高，随机页支持原生滚动。`DietaryGoalsUITests` 分别固定标准与最大辅助字号：标准字号逐项执行六项可访问性审计，最大字号逐项检查缩放、触控、裁切与语义；两种场景都实际选择、记忆、阅读并进入随机页。验证平台、结果和真实限制见 [验证记录](../../specs/dietary-goals/validation.md)，工程收尾与发布前人工条件见 [收尾清单](../../specs/dietary-goals/closeout.md)。

普通 UI 场景共用明确的初始展示方式、空目标和标准字号；重置发生在测试启动时，之后仍可通过真实控件切换。工作台测试在进入全屏前结束菜谱搜索，避免搜索状态影响布局和点击位置；饮食目标场景继续单独验证标准及最大辅助字号。

## 反馈入口

「我的 → 帮助与反馈」中的「需求墙」直接打开 `https://support.yunle.fun/feedback?product=cook`，保留 Cook 筛选；「支持中心」打开支持中心首页。意见反馈仍在 App 内私密提交，失败时保留内容并提供支持中心私密反馈入口，菜谱纠错保持原流程。

帮助页的入口共用设置列表的图标样式，并附用途说明；外部页面显示外链箭头。「微信客服」与网页版统一读取 [`packages/shared/links.json`](../../packages/shared/links.json)，通过系统打开微信客服页面。「App Store 评价」打开 `https://apps.apple.com/app/id6824589542?action=write-review`，直接前往云食册的商店评论页；应用正式上架后才能在商店评价。「意见反馈」保留问题、功能建议、求菜和其他类型，切换类型不会改变页面标题。「菜谱投稿」固定填写菜名、视频或图文链接以及可选补充说明，校验网页链接与完整内容的 500 字限额；提交时复用 `recipe` 类别，以「菜谱投稿」及字段名称标识内容。两种表单复用私密提交、联系方式和失败兜底，失败保留各字段草稿。

「微信客服」使用白色微信双气泡标识，沿用设置页 18 pt 图形、28 pt 圆角底板与系统绿色；SVG 以模板图像渲染。图标与链接的唯一源文件均在 [`packages/shared`](../../packages/shared/README.md)，Xcode 直接打包共享资产目录和 JSON，Web 直接导入同一资源；不保留 iOS 专属副本。修改资源后重新构建即可生效。

原生入口的发布与实际分发构建验收由 [#133](https://github.com/YunYouJun/cook/issues/133) 跟踪，源码合并不代表已经分发。

## 云乐坊账号

「我的 → AI 设置」可选择会员内置 DeepSeek，或保存自己的 DeepSeek Key。有效会员由服务器核验；普通用户仍可使用自己的 Key 或本地识别。在做菜搜索框输入想法后，点击「预览找菜条件」修改条件；模型请求仅在主动选择智能找菜并确认发送时发生。来源、额度、隐私与验证边界见[会员 AI 接入](../../docs/dev/ios-managed-ai.md)。

原生云乐坊登录的客户端登记、HTTPS 回调发布条件和会话验证见 [账号接入](../../docs/dev/ios-account.md)。微信登录暂缓，申请资料见 [微信移动应用申请](../../docs/dev/wechat-mobile-application.md)。登录与 iCloud 数据同步相互独立。

原生微信入口预留构建开关 `COOK_WECHAT_LOGIN_ENABLED`，Debug / Release 均默认 `NO`，通过 `Info.plist` 的 `CookWeChatLoginEnabled` 供 `CookFeatures.weChatLoginEnabled` 读取。只有显式 `YES` 才开启；缺失或未展开的值保持关闭。后续接入微信按钮和发起授权动作时，统一使用该属性控制入口。审核通过且 SDK、同一云乐坊账号绑定联调完成后，可在 `project.yml` 修改默认值并重新生成工程，或通过 `xcodebuild ... COOK_WECHAT_LOGIN_ENABLED=YES` 覆盖本次构建，再重新打包分发。

此开关目前仅预留配置，尚无原生微信按钮或 SDK 消费它；单独改成 `YES` 不会提供微信登录能力。开关不控制现有云乐坊授权网页、Universal Links 或 iCloud 同步，也不是远程开关。

## iCloud 同步的结构（CloudKit schema）

私人菜谱源码以最新 main 集成后的代码检查、Web/Swift 文件往返和 iPhone/iPad 模拟器测试作为合并条件。五模型生产结构、同账户跨设备读写，以及构建 6 的离线新增和修改 / 删除冲突已有[真实生产证据](../../docs/dev/ios-production-sync-2026-10-24.md)。维护者随后决定，剩余验收改用模拟器，不再要求真机；覆盖范围和不能由本地测试证明的账号行为见[模拟器验收记录](../../docs/dev/ios-simulator-acceptance-2026-10-24.md)。文件协议见[私人菜谱与文件迁移](../../docs/dev/private-recipes.md)。

收藏、浏览记录、做菜偏好、冰箱食材和私人菜谱用 SwiftData 存，登录 iCloud 时通过容器 `iCloud.fun.yunle.cook` 同步。TestFlight 和 App Store 版本用 CloudKit 的 **Production** 环境，结构要先在 Development 环境建好，再手动部署过去：

1. 用 Xcode 在**真机**上跑 Debug 版（已登录 iCloud）。第一次启动会调用 `initializeCloudKitSchema()` 把 `CD_Favorite`、`CD_HistoryEntry`、`CD_Preferences`、`CD_FridgeItem`、`CD_PrivateRecipeRecord` 建到 Development，首屏会多等几秒；日志里看到「已在 Development 环境建好 CloudKit 结构」就是成功了。结构版本 2 包含冰箱食材，版本 3 加入私人菜谱，之前完成过版本 1/2 的设备也会重新初始化。模拟器和 Release 构建不会跑这一步（见 `Cook/Model/CloudKitSchema.swift`）。
2. 打开 [CloudKit Console](https://icloud.developer.apple.com/dashboard/) → `iCloud.fun.yunle.cook` → Development → Record Types，确认这几个类型和字段都在。
3. 点左下角 **Deploy Schema Changes…**，检查变更只有新增（类型、索引和对应的默认权限），再点 Deploy。Production 的字段之后只能加不能删改，部署前想清楚。
4. 在 Production → Record Types 确认当前五个模型的结构已经同步过去，再用同一 Production 构建验证两台设备之间的收藏、历史、偏好、冰箱和私人菜谱读写，以及离线冲突、账号隔离和退出 iCloud 后的本地数据处理。记录版本、设备、环境、操作与观察结果；没有实际证据的项目保留为未验证。

改了 `@Model` 的字段时：把 `CloudKitSchema.version` 加一，真机 Debug 启动一次，然后重复第 2–4 步。如果在 Console 里 Reset 了 Development 环境，用启动参数 `-cloudkit.schemaInitialized 0` 让开发版重新建一次。

## 上架前 P0 验收

隐私政策正文维护在 [`docs/privacy.md`](../../docs/privacy.md)，由 XcodeGen 直接作为资源打包；修改正文后要重新构建 App。设置、统计同意页和意见反馈页都能离线打开政策。统计使用独立的同意记录，默认关闭，旧版默认开启不算用户同意；无反馈服务配置时不展示统计开关。

`CookTests` 覆盖同意迁移、撤回后的请求取消、收藏保存失败回滚与幂等重试、导入失败的原子回滚。`CookUITests` 使用独立同意记录和内存数据库，不读写用户数据、不发送反馈请求；覆盖正常收藏、连续失败重试、隐私政策入口和同意状态重启保留，以及拒绝统计后添加和移出冰箱食材。

2026-10-23 的生产检查记录与尚待完成的验收见 [iOS P0 验证记录](../../docs/dev/ios-p0-validation.md)。
