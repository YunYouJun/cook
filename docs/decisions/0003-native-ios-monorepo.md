# 0003 原生 iOS App 与 monorepo 结构

- 状态：提议中
- 日期：2026-10-18
- 关联：调整 [#98](https://github.com/YunYouJun/cook/issues/98) 的阶段 4

## 背景

- 目标从「只维护一套网页」变成「打磨一个能上架收费的 iOS App」，Web 保留并继续服务浏览器和云乐坊容器。
- 现在的独立 App 是 Capacitor 壳，界面是 Ionic 画的网页：拿不到 iOS 26 的 Liquid Glass、Widget、App Intents、Spotlight，CloudKit 同步也要自己写插件，作为付费 App 说服力和过审把握都不够。
- 仓库已经半个 monorepo：根目录是 Nuxt 应用，`packages/cook` 里有类型、CSV 转换、推荐（`recommend.ts`）和搜索（`search.ts`），核心匹配逻辑不到 200 行。

## 方案对比

| 问题         | 方案                                               | 取舍                                                       |
| ------------ | -------------------------------------------------- | ---------------------------------------------------------- |
| iOS 客户端   | Capacitor 壳（现状）                               | 一套代码；体验上限低，系统能力要逐个写插件                 |
|              | **SwiftUI 原生**                                   | 体验和系统能力最好；核心逻辑要用 Swift 再写一份            |
| 逻辑如何共享 | iOS 内嵌 JavaScriptCore 跑 TS 核心                 | 零重复；跨语言调试、类型和并发都别扭                       |
|              | **共享数据 + 共享测试用例，Swift 移植核心**        | 要写两份，但核心很小；同一套用例保证两端推荐结果一致       |
| 仓库结构     | 原生单独开仓库                                     | 数据和用例要跨仓库同步，容易漂移                           |
|              | **参考 starter-monorepo：`apps/*` + `packages/*`** | Web、iOS、数据、核心在一个仓库，一个 PR 能同时改数据和两端 |
| 同步         | 只用 CloudBase                                     | 三端通用，但要登录，服务器有成本                           |
|              | **CloudKit 免费 + CloudBase 会员跨平台**           | 免费用户零成本零登录；会员费覆盖服务器成本                 |

## 决定

### 目录结构

```
apps/
  web/        Nuxt 应用（现在根目录的 app/、public/、nuxt.config.ts 等）
  ios/        SwiftUI 工程（替换现在 Capacitor 的 ios/）
packages/
  data/       recipe.csv、incompatible-foods.csv、生成的 JSON、JSON Schema、dataVersion
  core/       纯 TS：类型、推荐、搜索、别名、相克判断，不依赖 Vue / Node
  cook/       CLI（convert / fetch / validate / eval），沿用 @yunyoujun/cook 包名
  fixtures/   两端共用的测试用例：输入食材 → 期望的菜谱 id 顺序
cloudfunctions/  不变
docs/            不变
```

- 搬迁只做 `git mv` 和路径修改，不改逻辑；搬完 `pnpm lint`、`typecheck`、`test`、`build` 全绿再合并。
- `packages/core` 从 `packages/cook` 里拆出；`@yunyoujun/cook/recommend` 保留重新导出，已有用法不受影响。
- iOS 侧有一个 Swift Package（`CookCore`），读同一份 JSON、跑同一份 `packages/fixtures`。推荐逻辑改动必须同时改两端，CI 里两边都跑用例。

### 数据

- JSON 加 `schemaVersion`（结构）和 `dataVersion`（内容）。App 内置一份，启动后可从 CDN 拉更新的 `dataVersion`，改菜谱不用发版。
- 菜谱 `id` 规则不变（见 [0002](./0002-feedback-and-ids)）：收藏、历史、同步都以 id 为主键。

### 同步与收费

- 本地数据库是唯一数据源：iOS 用 SwiftData，CloudKit 同步本地数据。
- 会员开启 CloudBase 作为第二个同步通道，与 Web 共用账号；两个云之间不直接同步。每条记录带 `updatedAt` 和软删除标记，冲突时取最新。
- iOS 里的会员走 StoreKit 内购；Web 购买的会员登录后可在 iOS 解锁（App Store 审核指南 3.1.3(b)）。
- App 免费下载 + 会员，不做付费下载，方便沿用现有商店条目和用户。

### 与 #98 的关系

- 阶段 1～3（云乐坊容器、数据同步）照常进行，Web 仍是浏览器和云乐坊的唯一代码。
- 阶段 4 改为：独立 App 不下架，而是用 SwiftUI 版本替换 Capacitor 版本，沿用 `cn.yunyoujun.cook`，老用户直接收到更新。Capacitor 相关代码在 SwiftUI 版上架后移除；`android/` 是否保留另行决定。

## 影响

- iOS 开发、构建和 UI 调试需要 macOS + Xcode 26，云端环境只能写代码和跑纯 Swift / TS 测试。
- 推荐逻辑的改动成本变成两份，靠共享用例兜底；核心逻辑应尽量保持小而纯。
- 搬迁会和进行中的 PR 冲突，需要等它们合并后再开始，且搬迁 PR 要尽快合并。
- 打磨计划和验收标准见 [iOS App 打磨目标](/dev/ios-roadmap)。
