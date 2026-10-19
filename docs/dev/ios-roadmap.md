# iOS App 打磨目标

目标：做出一个能在 App Store 上架、值得用户付费的「食用手册」iOS App，同时 Web 继续可用。背景和取舍见 [0003 原生 iOS App 与 monorepo 结构](/decisions/0003-native-ios-monorepo)。

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
- [ ] 在 Mac 上用 Xcode 打开 CookCore，确认 iOS 17 / macOS 14 能编译、测试通过。

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
   - Bundle id `cn.yunyoujun.cook`，显示名「云食册」，最低 iOS 17，用 Xcode 26 SDK 构建以获得 Liquid Glass。
   - 做菜页：按 `food.json` 分组的食材网格和厨具，推荐模式。
   - 筛选 sheet：做法、难度、不吃辣、忌口。
   - 相克提示、结果列表。
   - 菜谱详情：已有、常备和缺少的食材，B 站视频（有 B 站 App 就打开 App，否则用 Safari），分享。
2. **收藏、历史和设置**
   - SwiftData 模型：`Favorite`、`HistoryEntry` 以 recipe id 为主键，带 `updatedAt` 和软删除；`Preferences` 存常备调料、忌口和推荐模式。
   - CloudKit 容器 `iCloud.cn.yunyoujun.cook`。
   - 「我的」页：常备调料、关于、反馈。
3. **随机、iPad、无障碍和 CI**
   - 「今天吃什么」随机页。
   - iPad 用 `NavigationSplitView`。
   - 动态字体和 VoiceOver 标签。
   - macOS runner 上跑 `xcodegen` 和 `xcodebuild test`（iOS 模拟器）。

之后在 Mac 上 archive 并上传 TestFlight，构建号要高于现有 Capacitor 版。

需要准备：

- 装了 Xcode 26 的 Mac，以及 cook 仓库的本地克隆。
- Apple 开发者账号（`.env` 里的 `APPLE_DEVELOPMENT_TEAM`）。
- App Store Connect 里现有的 `cn.yunyoujun.cook` 条目，以及在开发者后台开启的 iCloud 容器。

验收：核心流程不依赖网络；冷启动到可点击不超过 1 秒（iPhone 12 级别设备）；滚动无明显掉帧；VoiceOver 能走完「选食材 → 打开菜谱」；TestFlight 内测无崩溃。

## M3 打磨

- Widget：「今天吃什么」随机推荐、最近收藏。
- App Intents / 快捷指令 / Siri：「我有土豆和鸡蛋，能做什么」。
- Spotlight 索引菜谱，系统分享面板，触感反馈，空状态和引导页。
- 菜谱数据在线更新（按 `dataVersion` 从 CDN 拉取），失败时用内置数据。
- 从 Capacitor 版迁移：首次启动导入旧版收藏和历史（方案待验证，备选是旧版先同步到云端）。

验收：每项功能有截图走查记录；旧版用户升级后收藏不丢；崩溃率和卡顿通过 Xcode Organizer 观察一周无异常。

## M4 会员与上架

- StoreKit 2 会员：CloudBase 跨平台同步，以及之后确定的高级功能；恢复购买；Web 已购会员登录后解锁。
- 付费墙、隐私清单、App Store 截图和文案、审核备注。
- 以更新的方式替换现有 `cn.yunyoujun.cook` 商店条目。

验收：沙盒环境走通购买、续订、退订、恢复；审核通过并上架。

## M5 收尾

- 移除 Capacitor（`ios/`、`capacitor.config.ts`、`@capacitor/*`、`nativeSso` 等）；`android/` 去留单独决定。
- 更新 #98 的阶段 4 和 `docs/dev/app.md`。

## 分工说明

- 云端 Claude 可以完成 M0、M1 的 TS 部分、Swift 代码编写和纯逻辑测试。
- Xcode 构建、模拟器和真机调试、TestFlight、App Store Connect 需要在你的 Mac 上进行（可以用 Remote Control 让 Claude 在你的电脑上跑）。
