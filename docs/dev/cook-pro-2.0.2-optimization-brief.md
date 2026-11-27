# Cook Pro 2.0.2 创意与体验优化清单（供 Claude Opus 5.5 精修）

> 目标：在 `2.0.1`（Build 31）通过 App Store 审核后，为 `2.0.2` 提供一轮轻量化、聚焦转化与情绪价值的付费页及入口体验打磨。

## 上下文与发布顺序

- **2.0.1 首发基线（请勿改动并立即提审）**：
  - 本地已归档并校验通过的候选包：`~/Library/Developer/ReleaseArtifacts/Cook/20261127-cook-pro-review-2.0.1-31.LkQ6uf7M/Cook-2.0.1-31.ipa`
  - IPA SHA-256：`d69125c9a8578afe98cf3ecbe7ddabad47799d32080a94802c9123e51eaa82ab`
  - 关联版本与商品：`v2.0.1` + 非消耗型内购 `fun.yunle.cook.pro_lifetime`（ASC IAP ID：`6838846130`）
  - 2.0.1 专用于先通过 App Store 审核（含 Build 31 对 Storefront 未解析前保持入口可见与可重载价格的修复），本清单所有改动均面向 **2.0.2**。

## 重点涉及文件

- [CookProPaywallView.swift](../../apps/ios/Cook/Views/CookProPaywallView.swift)
- [SettingsView.swift](../../apps/ios/Cook/Views/SettingsView.swift)
- [LearnedDishesView.swift](../../apps/ios/Cook/Views/LearnedDishesView.swift)
- [FridgeView.swift](../../apps/ios/Cook/Views/FridgeView.swift)
- [PrivateRecipesView.swift](../../apps/ios/Cook/Views/PrivateRecipesView.swift)
- [0005-app-icon.md](../decisions/0005-app-icon.md)

## 5 项创意优化方向

1. **付费页顶部「可交互 3D 菜品 Hero」**：
   - 将当前偏系统设置风格的顶部 `headerCard` 升级为可直观把玩的视觉焦点：直接嵌入 `DishShowcaseView`（支持手势旋转的 3D 菜品预览）或暖色调厨师徽章 Hero，让用户在购买前就能感受到 Pro 的质感与价值。
2. **个人进度感知型权益说明**：
   - 把静态的「免费 12 样 / 免费 3 道」文案替换为温和、贴合用户当前厨房状态的进度提示（例如：`冰箱已存 X/12 样 · 私房菜谱 Y/3 道 · 已学会 Z 道拿手菜`）。
   - 保持零压迫感：继续强调「已有食材与菜谱永不删除、不锁定」。
3. **买断成功仪式感 + Pro 限定 App 图标**：
   - 买断成功后，在 `已永久解锁` 状态反馈中加入轻触感与暖金色徽章/动效仪式感，避免只展示单一绿色静态标签。
   - 基于 [0005-app-icon.md](../decisions/0005-app-icon.md) 的统一俯视锅造型，额外提供 1–2 款 Pro 专属备选 App 图标（如深焙金边 / 暖陶釉色），在买断后解锁切换，提升情绪价值且零合规风险。
4. **情境化高亮与预览联动**：
   - 当用户从 `FridgeView`（`.fridge`）、`PrivateRecipesView`（`.privateRecipes`）或 `LearnedDishesView`（`.showcase`）触发 `CookProPaywallView(highlight:)` 时，不仅调整副标题，还将对应权益行置顶高亮；从拿手菜 3D 展柜进入时，顶部 Hero 优先预览当前选中的这道菜。
5. **国区底部卡片文案柔化**：
   - 待 2.0.1 审核通过且确认无硬性保留原文要求后，可将 `mainlandComplianceCard` 偏法务的提示语柔化为更温暖的一句话（如「一次买断，本机与 iCloud 权益永久解锁，无自动续费」）。

## 不可突破的红线（Guardrails）

- **地区与 AI 合规隔离**：中国大陆（`CHN`）严禁暴露云端 AI 订阅或任何在线生成式 AI 入口，常规及国区首发构建继续保持 `COOK_OVERSEAS_AI_ENABLED=NO`、`COOK_ONLINE_AI_ENABLED=NO`。
- **数据安全铁律**：免费额度仅限制新增超出上限的部分；已有冰箱食材和私房菜谱在任何情况下永不删除、不锁定、仍可编辑与导出。
- **Storefront 容错与自动化标识兼容**：
  - 无论 StoreKit 是否已返回 `storefrontCountryCode`，继续保持 Cook Pro 入口可见及「重新加载价格」可用。
  - 保留现有 `CookUITests` 依赖的所有 `accessibilityIdentifier`：`proPaywall.scroll`、`proPaywall.buyPro`、`proPaywall.reloadPrice`、`proPaywall.restore`、`proPaywall.privacy`、`proPaywall.terms`、`settings.pro`。

## 2.0.2 改动后的验收门禁

- `CookTests/CookProStoreTests`（含 Storefront 切换、额度与价格加载）
- `CookProStoreKit` Scheme（本地 StoreKit 2 购买与恢复购买）
- `CookUITests`（Paywall 入口、购买按钮、恢复购买与条款跳转）
- 辅助功能大字号（Dynamic Type）与浅色 / 深色模式（Light / Dark）实测无截断与对比度问题
