---
title: 食材模型展柜
aside: false
---

<script setup>
import IngredientGallery from '../.vitepress/theme/components/IngredientGallery.vue'
</script>

# 食材模型展柜

云厨房里的八样常用食材。选择一种，转动看看细节，再试试点选和入锅。这里的模型与网页厨房、iOS App 共用源文件，后续修改会一起更新。

<ClientOnly><IngredientGallery /></ClientOnly>

## 一套食材，三种用法

- **云厨房**：直接点食材选入篮子，点锅后沿弧线入锅。
- **食材篮**：使用模型渲染的透明小图；列表无需运行三维场景。
- **模型展柜**：只有一个三维预览，切换食材复用画布。其余缩略图保持静态，页面不可见时暂停。

「入锅试试」只演示动作；找真实菜谱请回到[云厨房首页](/)。系统开启「减少动态效果」时保留静态选中反馈。

## 后续维护

食材造型在 `docs/.vitepress/theme/cloud-kitchen/ingredients.ts`，名称、稳定 ID 和说明在同目录的 `ingredient-catalog.ts`。这里不单独维护另一套模型。

修改后，在仓库根目录执行：

```sh
pnpm exec tsx docs/scripts/export-cloud-kitchen.ts
```

导出使用 macOS 的 `usdcat`、`usdzip` 和 `usdrecord`：生成原生厨房 USDZ、每种食材的 USDZ、透明 PNG 和 iOS 图片资源。中间文件自动清理。详见[云厨房实现说明](./cloud-kitchen)。
