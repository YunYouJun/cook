# iOS App 打磨目标

目标：做出一个能在 App Store 上架、值得用户付费的「食用手册」iOS App（App 名「云食册」，见 [0004](/decisions/0004-ios-app-name)），同时 Web 继续可用。背景和取舍见 [0003 原生 iOS App 与 monorepo 结构](/decisions/0003-native-ios-monorepo)。

每个阶段完成验收项后才进入下一阶段。数字类指标是起始目标，做出第一版后按实测调整。

## M0 仓库改成 monorepo

- [x] 把 Nuxt 应用搬到 `apps/web`、菜谱 CSV 和生成的 JSON 搬到 `packages/data`，只改路径不改逻辑。根目录的 `pnpm build` 仍然输出到 `dist/`，CI、EdgeOne、Netlify、Vercel、Docker、Capacitor 不用改配置。
- [x] 食材词典、常备调料、别名改成 `packages/data` 里的 JSON（`food.json`、`pantry.json`、`aliases.json`），Web 和 iOS 读同一份。
- [x] 两端共用的推荐用例 `packages/data/fixtures/recommend.json`，`pnpm fixtures` 生成，`pnpm test` 检查是否过期。`packages/cook` 的推荐逻辑本身不依赖 Vue / Node，不再单独拆 `packages/core`。

验收：`pnpm lint`、`typecheck`、`test`、`build` 全绿；线上 Web 部署后行为不变；`pnpm eval:search` 指标与搬迁前一致。

## M1 数据与核心逻辑两端一致

- [x] `recipe-meta.json` 加 `schemaVersion`、`dataVersion`，`packages/data/schema` 里有每个 JSON 的 JSON Schema。
- [x] 共享用例补上相克判断（`incompatible.json`）和搜索（`search.json`）。
- [x] `apps/ios/CookCore`（Swift Package）实现菜谱加载、推荐、搜索、相克判断，CI 在 Linux 上跑 `swift test`。
- [ ] 收集到真实筛选信号后，把 `test/fixtures/search-eval.json` 也纳入共享用例。
- [x] 在 Mac 上用 Xcode 打开 CookCore，确认 iOS 17 / macOS 14 能编译、测试通过（Xcode 26，`swift test` 和 `xcodebuild test` 均通过）。

验收：TS 和 Swift 跑同一份用例全部通过；CI 两边都跑（Swift 部分可先用 Linux 上的 `swift test`，或 macOS runner）。

## M2 iOS 第一版（可上 TestFlight）

- App 名称「云食册 - 食用手册」，主屏显示「云食册」；Web 仍叫「食用手册」。
- 食材选择 → 推荐结果、菜谱详情（B 站视频跳转）、随机一道菜、收藏、浏览历史、设置。
- 只用系统控件搭界面：`TabView`、`NavigationStack`、`List`、sheet，自动获得 Liquid Glass。
- SwiftData 本地存储，CloudKit 同步收藏和历史。
- 深色模式、动态字体、VoiceOver、横竖屏和 iPad 基本可用。

### M2 实施计划

分三个 PR 交付，每个都能在模拟器里跑起来：

1. **工程骨架和做菜主流程**
   - `apps/ios/Cook` 用 XcodeGen 的 `project.yml` 生成工程，不提交手改的 pbxproj，避免冲突。
   - 依赖本地 CookCore。构建前运行 `pnpm convert`，构建脚本把 `packages/data` 的 JSON 拷进 App。
   - Bundle id `fun.yunle.cook`（见 [0006](/decisions/0006-ios-bundle-id)），显示名「云食册」，最低 iOS 17，用 Xcode 26 SDK 构建以获得 Liquid Glass。
   - 做菜页：按 `food.json` 分组的食材网格和厨具，推荐模式。
   - 筛选 sheet：做法、难度、不吃辣、忌口。
   - 相克提示、结果列表。
   - 菜谱详情：已有、常备和缺少的食材，B 站视频（有 B 站 App 就打开 App，否则用 Safari），分享。
2. **收藏、历史和设置**
   - SwiftData 模型：`Favorite`、`HistoryEntry` 以 recipe id 为主键，带 `updatedAt` 和软删除；`Preferences` 存常备调料、忌口和推荐模式。
   - CloudKit 容器 `iCloud.fun.yunle.cook`。
   - 「我的」页：常备调料、关于、反馈。
3. **随机、iPad、无障碍和 CI**
   - 「今天吃什么」随机页。
   - iPad 用 `NavigationSplitView`。按水平 size class 切换布局，不按设备型号判断：宽屏（iPad、iPhone Duo 展开的内屏、大屏 iPhone 横屏）做菜页左边选食材、右边实时出结果，菜谱页左列表右详情；窄屏（iPhone、折叠屏外屏、分屏后变窄）收成一栏。CI 在 iPhone 和 iPad 模拟器上各跑一遍 UI 测试。
   - 动态字体和 VoiceOver 标签。
   - macOS runner 上跑 `xcodegen` 和 `xcodebuild test`（iOS 模拟器）。

之后在 Mac 上 archive 并上传 TestFlight。

需要准备：

- 装了 Xcode 26 的 Mac，以及 cook 仓库的本地克隆。
- Apple 开发者账号（`.env` 里的 `APPLE_DEVELOPMENT_TEAM`）。
- 在开发者后台注册 `fun.yunle.cook` 并开启 iCloud 容器，在 App Store Connect 新建「云食册」条目。

验收：核心流程不依赖网络；冷启动到可点击不超过 1 秒（iPhone 12 级别设备）；滚动无明显掉帧；VoiceOver 能走完「选食材 → 打开菜谱」；TestFlight 内测无崩溃。

## M3 打磨

- Widget：「今天吃什么」随机推荐、最近收藏、冰箱里快过期的食材。
- App Intents / 快捷指令 / Siri：「我有土豆和鸡蛋，能做什么」。
- Spotlight 索引菜谱，系统分享面板，触感反馈，空状态和引导页。
- 菜谱数据在线更新（按 `dataVersion` 从 CDN 拉取），失败时用内置数据。
- 从 Capacitor 版迁移：首次启动导入旧版收藏和历史（方案待验证，备选是旧版先同步到云端）。

和 Web 对齐（已完成）：

- [x] 做菜页搜索框支持打字加食材和整句识别（CookCore 移植 `parseCookQuery`，跑 `fixtures/query.json`），识别结果写在列表顶部，词典里没有的食材单独列出。
- [x] 只选厨具也能看菜；结果页「再补一样」，点一下加上食材。
- [x] 「今天吃什么」按已选食材、厨具、忌口和筛选挑，摇一摇换一道。
- [x] 「我的」页导入网页版导出的收藏和浏览记录（只加不减），也能导出给网页版，在账号同步之前过渡。
- [x] iOS 26 起底部按钮用 Liquid Glass、Tab 栏下滑收小；菜谱行左滑收藏、长按预览。

新功能：

- [x] 「冰箱」记下家里的食材和到期日（默认按 `shelf-life.json` 估），快过期的排在前面，一键拿去找菜；本地通知做临期提醒，每天最多一条（[0010](/decisions/0010-fridge-reminders)）。

验收：每项功能有截图走查记录；旧版用户升级后收藏不丢；崩溃率和卡顿通过 Xcode Organizer 观察一周无异常。

## M4 会员与上架

- StoreKit 2 会员：CloudBase 跨平台同步，以及之后确定的高级功能；恢复购买；Web 已购会员登录后解锁。
- 付费墙、隐私清单、App Store 截图和文案、审核备注。App Store 名称「云食册 - 食用手册」，副标题「有什么食材，就做什么菜」。
- 云乐坊账号登录，与 Web 同步收藏和历史；关于页注明「原食用手册」。上架后在 `apps/web/app/constants/index.ts` 填写 `appStoreId`，Web 显示 App 横幅和 iOS 版入口。
- 作为新 App `fun.yunle.cook` 上架；Capacitor 版 `cn.yunyoujun.cook` 没有上架过，不需要迁移。

验收：沙盒环境走通购买、续订、退订、恢复；审核通过并上架。

## M5 收尾

- 移除 Capacitor（`ios/`、`capacitor.config.ts`、`@capacitor/*`、`nativeSso` 等）；`android/` 去留单独决定。
- 更新 #98 的阶段 4 和 `docs/dev/app.md`。

## 分工说明

- 云端 Claude 可以完成 M0、M1 的 TS 部分、Swift 代码编写和纯逻辑测试。
- Xcode 构建、模拟器和真机调试、TestFlight、App Store Connect 需要在你的 Mac 上进行（可以用 Remote Control 让 Claude 在你的电脑上跑）。
