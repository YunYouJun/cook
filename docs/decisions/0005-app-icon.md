# 0005 应用图标「蒸汽成云」

- 状态：已采纳
- 日期：2026-10-19
- 关联：[0004 iOS App 命名为「云食册」](./0004-ios-app-name)

> 2026-11-25：Android、网页版和备案素材也采用 iOS 的俯视锅图形，见下文。

## 背景

- iOS 版改名「云食册」并挂上云乐坊品牌（见 0004），原来的祖母绿俯视锅图标和云乐坊系列（云乐盘、云生、云匣）的视觉不统一。
- 云乐坊系列图标规范在 `YunLeFun/icons`：晴空蓝渐变 `#55AAFF → #2563EB` 底板、云白主体、明黄 `#FACC15` 点缀，64 × 64 参考网格，`-app-icon` 满幅不预切圆角，`-mark` 透明。
- 更早的线条 Logo 是「锅 + 勺子 / 热气」，老用户对锅有印象。

## 方案对比

| 方案                         | 取舍                                                 |
| ---------------------------- | ---------------------------------------------------- |
| 云碗（米饭堆成云 + 筷子）    | 小尺寸最清楚，和老 Logo 没有延续                     |
| 食册（菜谱封面镂空云碗）     | 对得上「册」，但书本图形泛用，20pt 下碗看不清        |
| 锅里的 ▲●                    | 品牌符号最直接，但看不出是吃的，且 ▲● 已是云匣的主体 |
| 线条焕新                     | 最像老 Logo，线条在实心的系列里单薄                  |
| 冒热气的菜谱、云做锅盖       | 元素多，小尺寸下热气或横线糊掉                       |
| **蒸汽成云（锅 + 勺 + 云）** | 留住老 Logo 的锅和勺，热气就是云，对上「云」和「食」 |

## 原决定

- 采用「蒸汽成云」：白色锅、锅里冒出的热气是一朵云（轮廓取自云乐盘），明黄长勺斜插在锅里。2026-11-14 缩小云并靠近锅口、收细锅沿，底板改为与「找菜」呼应的菜叶绿渐变 `#438453 → #2F6B45`。
- 源文件在 `apps/web/public/brand/`：`cook-app-icon.svg`（浅色）、`cook-app-icon-dark.svg`（深色）、`cook-app-icon-tinted.svg`（着色灰度原稿）、`cook-mark.svg`（透明 Logo）。其他尺寸都从这些 SVG 导出，不手改 PNG。
- 深色版用近黑底板 `#111111`，云、锅和勺子统一为白色。着色原稿用黑底白主体、勺子中灰，由系统上色。
- Web favicon 加 14 的圆角；Safari 固定标签页用单色 Logo。
- Android 的原方案是同系列渐变（`drawable/ic_launcher_gradient.xml`），前景放大 1.09 倍放进 108dp 画布中央，落在 66dp 安全区内；当前仓库不包含 Android 工程。
- 原生 iOS 用 Icon Composer 分层：默认版的云锅使用系统高光和轻微折射，深色版的云锅保持实白。所有外观保留锅沿和锅身之间的细缝，透明和着色外观由系统渲染。

## 影响

- `YunLeFun/icons` 的 `cook-mark` / `cook-app-icon` 需要重新同步；它的 metadata 里来源路径还是旧的 `public/brand/`，应改成 `apps/web/public/brand/`。
- 之后改图标先改 SVG，再重新导出各平台 PNG，并更新 `test/nativeAppMetadata.test.ts`。

## iOS 俯视锅 · 2026-11-17

云食册主屏幕改用工坊「今天来做菜」的[俯视锅图标](https://github.com/YunYouJun/cook/blob/7bc18f58bf8359ea1f56d985b1aa48364d663502/public/brand/cook-app-icon.svg)。保留祖母绿、双耳锅和红黄绿食材，让两个入口容易认作同一产品。

`apps/ios/Cook/Resources/AppIcon.icon` 是 iOS 图标源文件。底色由 Icon Composer 绘制，锅沿为独立矢量层，使用系统高光与轻微折射；锅底、食材用实色。双耳镂空、锅沿与锅内的间隙、番茄星形留白在几个外观中沿用同一轮廓。深色降低底色亮度；Mono 用明度区分食材，由系统生成 Clear 和 Tinted。所有图层不预切圆角，也不预绘阴影。

修改矢量或配色后，在仓库根目录运行 `node apps/ios/scripts/export-app-icon.mjs`，重新导出 iOS 默认、深色和着色 1024 px 静态图。

## 统一俯视锅图标 · 2026-11-25

- iOS 的 `AppIcon.icon` 提供俯视锅的路径、图层顺序和配色。Android 使用同一图形的自适应启动图标，将前景缩放到系统安全区内。
- `apps/web/public/brand/` 提供满幅 SVG、深色和着色版本及透明标记，PWA、Apple 触屏图标和 favicon 使用同一造型。
- `docs/public/brand/cook-app-icon.png` 是备案和商店使用的 512 × 512 满幅 PNG，与 Web 的 `pwa-512x512.png` 共用同一导出。
