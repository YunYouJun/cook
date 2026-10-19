# iOS App 打磨目标

目标：做出一个能在 App Store 上架、值得用户付费的「食用手册」iOS App，同时 Web 继续可用。背景和取舍见 [0003 原生 iOS App 与 monorepo 结构](/decisions/0003-native-ios-monorepo)。

每个阶段完成验收项后才进入下一阶段。数字类指标是起始目标，做出第一版后按实测调整。

## M0 仓库改成 monorepo

- [x] 把 Nuxt 应用搬到 `apps/web`、菜谱 CSV 和生成的 JSON 搬到 `packages/data`，只改路径不改逻辑。根目录的 `pnpm build` 仍然输出到 `dist/`，CI、EdgeOne、Netlify、Vercel、Docker、Capacitor 不用改配置。
- [ ] 拆出 `packages/core`、`packages/fixtures`，`packages/cook` 只保留 CLI；`food.ts` 改成 `packages/data` 里的 JSON，iOS 也能读。

验收：`pnpm lint`、`typecheck`、`test`、`build` 全绿；线上 Web 部署后行为不变；`pnpm eval:search` 指标与搬迁前一致。

## M1 数据与核心逻辑两端一致

- JSON 加 `schemaVersion`、`dataVersion` 和 JSON Schema。
- `packages/fixtures` 从 `test/fixtures/search-eval.json` 和现有推荐测试整理出共享用例。
- `apps/ios/CookCore`（Swift Package）实现菜谱加载、推荐、搜索、相克判断。

验收：TS 和 Swift 跑同一份用例全部通过；CI 两边都跑（Swift 部分可先用 Linux 上的 `swift test`，或 macOS runner）。

## M2 iOS 第一版（可上 TestFlight）

- 食材选择 → 推荐结果、菜谱详情（B 站视频跳转）、随机一道菜、收藏、浏览历史、设置。
- 只用系统控件搭界面：`TabView`、`NavigationStack`、`List`、sheet，自动获得 Liquid Glass。
- SwiftData 本地存储，CloudKit 同步收藏和历史。
- 深色模式、动态字体、VoiceOver、横竖屏和 iPad 基本可用。

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
