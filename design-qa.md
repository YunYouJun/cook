# Design QA — 云食册 / 食用手册应用图标

- Date: 2026-10-19
- Visual target: 「蒸汽成云」（[0005](docs/decisions/0005-app-icon.md)）
- Surfaces: Web/PWA, Capacitor iOS launcher, Android launcher

## Visual fidelity

- Follows the YunLeFun icon spec: sky gradient `#55AAFF → #2563EB`, cloud-white glyph, `#FACC15` accent, 64 × 64 reference grid.
- The cloud reuses the 云乐盘 cloud outline so the icon sits in the same family as 云乐盘, 云生 and 云匣.
- Keeps the pot and ladle from the earlier line logo; the steam becomes the cloud.
- `cook-app-icon` fills the square with no corner mask or baked shadow; `cook-mark` is transparent.

## Platform checks

- 20, 29, 40 and 64 pt previews keep the pot, cloud and ladle distinguishable in light and dark.
- iOS asset catalog ships light, dark and tinted 1024 px variants; all PNGs are opaque RGB.
- Android adaptive foreground stays inside the 66 dp safe zone over the gradient background drawable.
- Favicon uses a 14-unit corner radius; the Safari pinned tab uses a single-color mark.

## Build gates

- Cook lint and the full Vitest suite passed (including `test/nativeAppMetadata.test.ts`).

final result: passed
