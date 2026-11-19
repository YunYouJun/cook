---
name: cross-platform-sync
description: 当修改 packages/cook 核心算法、packages/data 数据结构或 AI/用户数据契约时，同步更新 Web (TS)、iOS (Swift CookCore) 与 Android (Kotlin core) 并以共享 fixtures 和三端单测作为闭环验收。维护者说"同步三端""对齐 iOS 和 Android""更新共享逻辑/契约"时使用。
metadata:
  author: YunYouJun
  repository: https://github.com/YunYouJun/cook
---

# 跨端核心逻辑同步（Web / iOS / Android）

Cook 以 TypeScript (`packages/cook`) 和共享契约 (`packages/data/fixtures/*.json`、`packages/data/schema/`) 为单一事实源（Source of Truth），由 iOS (`apps/ios/CookCore`) 与 Android (`apps/android/core`) 镜像实现。

任何核心算法、解析规则、数据字段或契约变更，都必须按本流程闭环完成三端对齐，禁止只改一端留下隐性漂移。

## 三端核心文件映射表

| 能力模块          | TypeScript (`packages/cook/src/utils/`)                                        | iOS Swift (`apps/ios/CookCore/Sources/CookCore/`) | Android Kotlin (`apps/android/core/src/main/kotlin/cn/yunyoujun/cook/core/`) | 共享契约 / 用例                                                              |
| ----------------- | ------------------------------------------------------------------------------ | ------------------------------------------------- | ---------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| 推荐与排序        | `recommend.ts`                                                                 | `Recommender.swift`                               | `recommend/Recommender.kt`                                                   | `packages/data/fixtures/recommend.json`                                      |
| 食材别名归一化    | `alias.ts` + `recommend.ts`                                                    | `StuffNormalizer.swift`                           | `recommend/StuffNormalizer.kt`                                               | `packages/data/aliases.json` + `recommend.json`                              |
| 食物相克          | `incompatible.ts`                                                              | `Incompatibility.swift`                           | `recommend/Incompatibility.kt`                                               | `packages/data/fixtures/incompatible.json`                                   |
| CLI / 快捷搜索    | `search.ts`                                                                    | `Search.swift`                                    | `search/Search.kt`                                                           | `packages/data/fixtures/search.json`                                         |
| 自然语言整句解析  | `query.ts`                                                                     | `Query.swift`                                     | `search/QueryParser.kt`                                                      | `packages/data/fixtures/query.json`（手写）                                  |
| 私人菜谱同步      | `custom-recipes.ts`                                                            | `PrivateRecipes.swift`                            | `sync/PrivateRecipes.kt`                                                     | `scripts/test-private-recipe-roundtrip.sh`                                   |
| 个人清单同步      | `personal-catalog.ts`                                                          | `PersonalCatalog.swift`                           | `sync/PersonalCatalog.kt`                                                    | `scripts/test-personal-catalog-roundtrip.sh`                                 |
| 饮食目标 / 低 GI  | `reviewed-data.ts`                                                             | `DietaryGoals.swift` + `InstitutionalLowGI.swift` | `dietary/DietaryGoals.kt` + `dietary/InstitutionalLowGI.kt`                  | `pnpm dietary:validate`                                                      |
| AI 意图与引导契约 | `scripts/generate-ai-intent-contract.mjs` / `generate-ai-guidance-sources.mjs` | `SearchIntent.swift` + `StepGuidance.swift`       | `ai/SearchIntent.kt` + `ai/StepGuidance.kt`                                  | `AiAndNativeContractsTest.kt` + `node --test test/aiIntentContract.test.mjs` |

## 硬性规则

1. **先读相关 ADR**：开始前检查 `docs/decisions/` 中与该模块相关的决策记录；涉及方向或数据结构破坏性变化时，先补或更新 ADR。
2. **破坏性 Schema 变更同步升版本**：如果字段改名、删除或改类型，必须同步递增 `DATA_SCHEMA_VERSION`（`packages/cook/src/utils/config.ts`、iOS `CookCore`、Android `core`）并更新 `packages/data/schema/`。
3. **只以共享 Fixtures 和三端测试全绿为完成标准**：排序结果、匹配字段、顺序与边界条件在三端必须完全一致。
4. **不要手改生成的 fixtures**：`recommend.json`、`search.json`、`incompatible.json` 必须通过 `pnpm fixtures` 生成；只有 `query.json` 是手写维护的。

## 执行步骤

1. **更新事实源（TS / Data / 生成脚本）**
   - 修改 `packages/cook` 或 `packages/data` 中的源逻辑/数据。
   - 若改动涉及整句解析或边界行为，同步在 `packages/data/fixtures/query.json` 或 `scripts/lib/fixtures.ts` 补充回归用例。
   - 若改动涉及 AI 意图、引导来源或用户数据契约，重新运行对应生成脚本并检查 diff：
     ```bash
     node scripts/generate-ai-intent-contract.mjs --check
     node scripts/generate-ai-guidance-sources.mjs --check
     ```

2. **重新生成共享产物**

   ```bash
   pnpm convert
   pnpm fixtures
   ```

3. **同步 iOS (`apps/ios/CookCore`) 与 Android (`apps/android/core`)**
   - 对照上表定位对应的 Swift 与 Kotlin 文件，逐条镜像逻辑与边界条件（特别注意空数组、大小写、去重顺序、平局 tie-break 和默认分数 `DEFAULT_RECIPE_SCORE`）。
   - 如果新增了测试场景，确认 `apps/ios/CookCore/Tests/` 与 `apps/android/core/src/test/` 的共享契约测试都能覆盖。

4. **三端闭环验证（Looping Until Green）**

   ```bash
   # 1. Web / TS 核心 + fixtures 一致性 + 闭环检查
   pnpm audit:loop --check
   pnpm test -- --run
   pnpm typecheck

   # 2. iOS CookCore（macOS / Linux Swift 环境）
   swift test --package-path apps/ios/CookCore

   # 3. Android Kotlin core（需 JDK 21）
   (cd apps/android && ./gradlew :core:test --no-daemon)
   ```

   如果当前环境缺少 Swift 或 JDK/Android SDK，明确在汇报/PR 中写清「哪一端已在本地验证、哪一端交由 GitHub Actions（`ci.yml` / `android.yml`）验证」，不要伪造通过结果。
