# 0004 iOS App 命名为「云食册」

- 状态：已采纳
- 日期：2026-10-19
- 关联：[0003 原生 iOS App 与 monorepo 结构](./0003-native-ios-monorepo)

## 背景

- SwiftUI 版 iOS App 准备上架，沿用 `cn.yunyoujun.cook` 替换现有 Capacitor 版。
- iOS 版会做会员和付费（见 0003），希望和免费的网页版有所区分，同时挂上云乐坊的品牌。
- 「食用手册」已有知名度，Web、GitHub、B 站和公众号都用这个名字，改名不能丢掉老用户和搜索流量。

## 方案对比

| 方案                        | 取舍                                                         |
| --------------------------- | ------------------------------------------------------------ |
| 两端都叫「食用手册」        | 最稳，零迁移成本；和云乐坊、付费版没有区分                   |
| 「云食」「云食谱」          | 「云食」太泛，容易被理解成云南菜或外卖；「云食谱」像云端存储 |
| 「云食用手册」              | 「云食用」在网络用语里是「看别人吃」，和做菜相反，也偏长     |
| **iOS「云食册」，Web 不变** | 「云」对应云乐坊，「食…册」接得上原名；两个名字需要互相指引  |

## 决定

- iOS 主屏幕显示名 `云食册`（`CFBundleDisplayName`），App Store 名称 `云食册 - 食用手册`，副标题 `有什么食材，就做什么菜`。搜「食用手册」仍能搜到。
- Web、PWA、Android 继续叫「食用手册」，`capacitor.config.ts` 的 `appName` 不变（它只在 `cap add` 时生效）。
- 两个名字互相指引，避免用户以为是两个产品：
  1. App Store 名称带上原名。
  2. Web 在 `appStoreId` 填写后输出 `apple-itunes-app` meta，Safari 顶部显示 App 横幅。
  3. 关于页：iOS App 内显示「云食册 · 原食用手册」，Web 显示「iOS 版：云食册」并链接到 App Store。
  4. 两端都用云乐坊账号登录，收藏和浏览历史通过 CloudBase 同步（Web 已实现，见 `useUserDataSync`；iOS 在 M4 接入，收费方式按 0003）。
- 常量在 `apps/web/app/constants/index.ts`：`iosAppName`、`appStoreId`。

## 影响

- 上架或拿到现有商店条目的 App ID 后，填写 `appStoreId` 才会出现横幅和关于页入口。
- SwiftUI 版的关于页、App Store 截图和文案按上面的说法写。
- 如果 iOS 版做起来，之后可以考虑把 Web 也统一成「云食册」，届时新写一篇决定。
