# 0006 iOS App 使用 `fun.yunle.cook`

- 状态：已采纳
- 日期：2026-10-19
- 关联：取代 [0003](./0003-native-ios-monorepo) 里「沿用 `cn.yunyoujun.cook`」的部分

## 背景

0003 计划让 SwiftUI 版沿用 Capacitor 版的 `cn.yunyoujun.cook`，以更新的方式推给老用户。但 Capacitor 版从未在 App Store 上架，没有老用户、评分和商店条目需要继承。bundle id 上架后无法修改，现在是唯一能换的时机。

## 方案对比

| 方案                     | 取舍                                                  |
| ------------------------ | ----------------------------------------------------- |
| 沿用 `cn.yunyoujun.cook` | 不用新注册；但挂在个人域名下，和云乐坊品牌无关        |
| **`fun.yunle.cook`**     | 跟云乐坊域名 `yunle.fun` 一致，和「云食册」的定位相符 |

## 决定

- 原生 iOS App 的 bundle id 为 `fun.yunle.cook`，测试 target 为 `fun.yunle.cook.tests`、`fun.yunle.cook.uitests`。
- CloudKit 容器为 `iCloud.fun.yunle.cook`。容器建了就删不掉，所以上传 TestFlight 前一并定下来。
- 在 App Store Connect 新建「云食册」条目，版本从 2.0.0（build 1）开始。
- Capacitor 壳（`capacitor.config.ts`、`android/`）和 Web 的 `apple-app-site-association` 暂不改，等 Capacitor 版移除时一起处理。此清理安排已由 2026-10-26 的 [0016](./0016-native-android-and-capacitor-retirement) 取代，本文保留当时决定。

## 影响

- 需要在开发者后台注册 `fun.yunle.cook` 的 App ID 并开启 iCloud（Xcode 自动签名可能会自动完成）。
- 以后如果要给 iOS App 配置通用链接，`apple-app-site-association` 要换成新的 App ID。
