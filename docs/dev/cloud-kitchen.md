# 云厨房原生小样

云厨房是「做菜」的另一种呈现方式，不单独增加 Tab。iOS 导航栏右上角的按钮可一键切换列表和云厨房：列表中显示立方体图标，厨房中显示列表图标，不占用额外的内容行。两种视图共用 `CookSession` 的食材、厨具和筛选条件。展示偏好保存在 `cook.presentation`，选择食材后点锅或底部开火按钮，进入现有 `ResultsView` 和菜谱详情。

## 共享模型

厨房布局在 `docs/.vitepress/theme/cloud-kitchen/objects.ts`，8 种食材的共享模型在同目录的 `ingredients.ts`。可在[食材模型展柜](./ingredient-models)中查看、旋转、试点选和入锅动画，下载单个 USDZ 与透明 PNG。iOS 使用同一份几何模型导出的 USDZ，由 RealityKit 原生渲染，无需 WebView、网络或相机权限。

在仓库根目录运行：

```sh
pnpm exec tsx docs/scripts/export-cloud-kitchen.ts
```

脚本生成 `apps/ios/Cook/Resources/CloudKitchen.usdz` 和 `CloudKitchenManifest.json`。清单记录食材节点、中文食材名、相机取景参数；模型保留锅、选择圈、火焰和蒸汽节点。脚本还会生成文档展示用的单食材 USDZ、透明 PNG 和 iOS 图片资源。修改模型后运行一次导出即可同步。需要 macOS 自带的 `usdcat`、`usdzip` 和 `usdrecord`，使用标准 USDC 二进制层，不依赖新式有损压缩。

当前模型为 121 个网格、38,212 个三角形，二进制 USDZ 约 2.77 MiB。相较首版 10.09 MiB，体积减少约 73%。材质和光照在网页与 RealityKit 中分别渲染，存在色彩和阴影差异；不承诺像素一致。

## 原生实现

- `CookView` 管理切换和基于值的导航；两种界面共用真实推荐器。
- `Views/CloudKitchen/CloudKitchenView.swift` 提供食材篮、筛选、开火和加载失败时的操作入口。
- `CloudKitchenSceneView.swift` 负责原生模型加载、适配取景、点击命中、选中状态和短暂开火动画。
- 模型提供食材及锅的 VoiceOver 操作；小食材使用投影位置周围的点击区域。
- 点选有短暂弹起和缩放反馈；点锅后复制已选食材沿弧线入锅，约 1.2 秒后进入真实推荐。取消、切换和离页会清除飞行副本。
- 食材篮用同一模型渲染的透明 PNG；列表不逐个建立三维渲染器。
- 离开页面或进入后台时卸载场景；取消加载和动画订阅。减少动态效果开启时跳过过渡动画。
- 模型只放置 8 样常用食材；「全部食材」可选择完整目录，额外选择仍参与推荐。

首轮验证在 iPhone 15 Pro 模拟器完成，尚未做真机耗电、热量及最低支持系统的性能验收。

## 网页首屏

落地页直接使用云厨房，不再提供「精致生活 / 轻游戏」切换。保留明暗主题及下方产品展示。鼠标在模型区域移动时，真实相机做小幅平滑偏转，离开区域后回正；触屏和减少动态效果模式不启用跟随视角。

## 验证

- `CookTests/CloudKitchenAssetTests` 使用 RealityKit 实际加载导出资源，验证锅、食材和选择圈节点及真实食材目录映射。
- `CookUITests/CloudKitchenUITests` 验证列表选择 → 云厨房同步 → 点击模型食材 → 切回列表 → 点击锅 → 真实番茄炒蛋推荐 → 详情 → 返回厨房，及空食材时的提示。
- 网页检查生产构建、类型检查、菜谱数据测试，以及桌面/窄屏、明暗模式和减少动态效果。
