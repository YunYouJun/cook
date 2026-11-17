# 三端共享核心契约与联动 CI 测试清单

本文档定义 Web（TypeScript / [packages/cook](https://github.com/YunYouJun/cook/tree/main/packages/cook)）、iOS（Swift / [CookCore](https://github.com/YunYouJun/cook/tree/main/apps/ios/CookCore)）与 Android（Kotlin / [apps/android/core](https://github.com/YunYouJun/cook/tree/main/apps/android/core)）三端在保持「UI 与系统集成 100% 原生」的前提下，如何通过**共享 Fixtures + Linux 三端同跑 CI 门禁**彻底防止核心规则与协议偏移。

---

## 1. 设计原则

1. **不在端内强插跨端 UI 或共享二进制黑盒**
   - Web（Nuxt + Ionic）、iOS（SwiftUI）、Android（Jetpack Compose + Material 3）各自保持纯原生开发、调试与界面交互惯例。
2. **用共享 Fixtures 锁定行为结果与顺序**
   - 一切算法（推荐排序、搜菜、整句分词、相克、份数缩放、用量步骤余量、饮食目标与机构低 GI 准入、用户同步合并、私人菜谱与个人词典格式）必须对 `packages/data/fixtures/*.json` 产出**字节级一致的字段与顺序结果**。
3. **三端纯核心全部跑在低成本 Linux CI 上**
   - Web Vitest、Swift `CookCore`（`ubuntu-24.04` + Swift toolchain）与 Android `:core`（`ubuntu-latest` + JVM 17/Gradle）均不依赖模拟器或 macOS Runner，可在 PR 阶段数秒至一分钟内同时拦截任一端逻辑漂移。

---

## 2. 共享 Fixtures 与三端测试对照矩阵

| 核心模块                               | 共享输入 / Fixture 文件                                            | Web (Vitest / Node Test)                                                                                                                                                                                                                                                                                                                                                        | iOS (`CookCoreTests`)                                                                                                                                                                                                                                                                                                                                                                                                                              | Android (`:core:test`)                                                                                                                                          |
| -------------------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **推荐 / 搜索 / 整句 / 相克 / 归一化** | `recommend.json`, `search.json`, `query.json`, `incompatible.json` | [fixtures.test.ts](https://github.com/YunYouJun/cook/blob/main/test/fixtures.test.ts), [query.test.ts](https://github.com/YunYouJun/cook/blob/main/test/query.test.ts)                                                                                                                                                                                                          | [SharedFixtureTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/SharedFixtureTests.swift)                                                                                                                                                                                                                                                                                                             | [SharedFixtureTests.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/SharedFixtureTests.kt)             |
| **菜谱正文块与展示提取**               | `recipe-text-blocks.json`                                          | [sharedCoreContracts.test.ts](https://github.com/YunYouJun/cook/blob/main/test/sharedCoreContracts.test.ts)                                                                                                                                                                                                                                                                     | [SharedCoreContractTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/SharedCoreContractTests.swift), [RecipeTextTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/RecipeTextTests.swift)                                                                                                                                                                 | [SharedCoreContractTests.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/SharedCoreContractTests.kt)   |
| **份数缩放 / 步骤公式 / 余量分配**     | `recipe-amounts-scaling.json`                                      | [recipeAmounts.test.ts](https://github.com/YunYouJun/cook/blob/main/test/recipeAmounts.test.ts), [recipeAmountAllocations.test.ts](https://github.com/YunYouJun/cook/blob/main/test/recipeAmountAllocations.test.ts), [sharedCoreContracts.test.ts](https://github.com/YunYouJun/cook/blob/main/test/sharedCoreContracts.test.ts)（构建期 AST、缩放求值、原文锚点与非法式拒绝） | [SharedCoreContractTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/SharedCoreContractTests.swift), [RecipeAmountsTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/RecipeAmountsTests.swift), [RecipeAmountAllocationTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/RecipeAmountAllocationTests.swift) | [SharedCoreContractTests.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/SharedCoreContractTests.kt)   |
| **饮食目标与机构低 GI**                | `dietary-contracts.json`                                           | [dietary.test.ts](https://github.com/YunYouJun/cook/blob/main/test/dietary.test.ts), [institutionalLowGI.test.ts](https://github.com/YunYouJun/cook/blob/main/test/institutionalLowGI.test.ts), [sharedCoreContracts.test.ts](https://github.com/YunYouJun/cook/blob/main/test/sharedCoreContracts.test.ts)                                                                     | [SharedCoreContractTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/SharedCoreContractTests.swift), [DietaryGoalsTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/DietaryGoalsTests.swift), [InstitutionalLowGITests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/InstitutionalLowGITests.swift)           | [SharedCoreContractTests.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/SharedCoreContractTests.kt)   |
| **用户云同步合并与边界**               | `user-data-sync.json`, `user-data-sync-boundaries.json`            | [userData.test.ts](https://github.com/YunYouJun/cook/blob/main/test/userData.test.ts), [userDataContract.test.ts](https://github.com/YunYouJun/cook/blob/main/test/userDataContract.test.ts)                                                                                                                                                                                    | [UserDataDocumentTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/UserDataDocumentTests.swift)                                                                                                                                                                                                                                                                                                       | [UserDataDocumentTest.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/sync/UserDataDocumentTest.kt)    |
| **个人词典 / 私人菜谱 JSON**           | `personal-catalog.json`, `private-recipes.json`                    | [personalCatalog.test.ts](https://github.com/YunYouJun/cook/blob/main/test/personalCatalog.test.ts), [privateRecipes.test.ts](https://github.com/YunYouJun/cook/blob/main/test/privateRecipes.test.ts)                                                                                                                                                                          | [PersonalCatalogTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/PersonalCatalogTests.swift), [PrivateRecipeTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/PrivateRecipeTests.swift)                                                                                                                                                                 | [SharedCoreContractTests.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/SharedCoreContractTests.kt)   |
| **数据版本与资源清单**                 | `recipe-meta.json`, `links.json`                                   | [sharedResources.test.ts](https://github.com/YunYouJun/cook/blob/main/test/sharedResources.test.ts)                                                                                                                                                                                                                                                                             | `CookData` (`supportedSchemaVersion == 1`)                                                                                                                                                                                                                                                                                                                                                                                                         | [CookDataLoaderTest.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/data/CookDataLoaderTest.kt)        |
| **AI 协议与数字保护**                  | `step-guidance-numbers.json`                                       | [aiIntentContract.test.mjs](https://github.com/YunYouJun/cook/blob/main/test/aiIntentContract.test.mjs), [aiGuidanceSources.test.mjs](https://github.com/YunYouJun/cook/blob/main/test/aiGuidanceSources.test.mjs)                                                                                                                                                              | [SearchIntentTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/SearchIntentTests.swift), [StepGuidanceTests.swift](https://github.com/YunYouJun/cook/blob/main/apps/ios/CookCore/Tests/CookCoreTests/StepGuidanceTests.swift)                                                                                                                                                                         | [AiAndNativeContractsTest.kt](https://github.com/YunYouJun/cook/blob/main/apps/android/core/src/test/kotlin/cn/yunyoujun/cook/core/AiAndNativeContractsTest.kt) |

---

## 3. GitHub Actions 三端联动 CI 策略

配置位于 [.github/workflows/ci.yml](https://github.com/YunYouJun/cook/blob/main/.github/workflows/ci.yml) 、[android.yml](https://github.com/YunYouJun/cook/blob/main/.github/workflows/android.yml) 与 [scripts/ci-changes.mjs](https://github.com/YunYouJun/cook/blob/main/scripts/ci-changes.mjs)：

1. **变更判定沿用 fail-closed 原则**：
   - 仅当**所有修改文件**均属于文档、纯浏览器端界面/适配器（`isWebOnly`）或独立 Support 脚本时，`skip_native` 才为 `true`。
   - 只要改动命中 `packages/data/**`、`packages/cook/**`、`packages/shared/**`、`apps/ios/**`、`apps/android/**`、`cloudfunctions/**`、`scripts/**` 或未分类路径，`skip_native = false`：
     - **`test (ubuntu-latest)`**：执行 `pnpm run convert`、AI 协议校验、`pnpm run validate`、`pnpm run amounts:check` 与 `pnpm run test`（包含 `fixtures.test.ts` 与 `sharedCoreContracts.test.ts`）。
     - **`swift (ubuntu-24.04)`**：执行 `pnpm run convert`、`swift test --package-path apps/ios/CookCore` 与私人菜谱/个人词典往返脚本。
2. **Android 独立工作流**：Android 源码、共享数据与资源、反馈接口或构建依赖变化时，在 `ubuntu-latest` 执行 `pnpm run convert`、`./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`（同步触发 `syncCookAssets` 白名单拷贝、全量 `:core` + Robolectric Room/App 集成测试及 R8 Release 构建验证），并在 `--offline` 模式确认依赖可复用。

---

## 4. 本地改动提交前自查清单

修改任何共享数据、推荐/搜菜/用量/饮食规则或三端同步格式后，在仓库根目录顺次执行以下命令验证三端核心一致：

```bash
# 1. 重新生成数据与基础 fixtures（如改动了 CSV / 推荐 / 搜索规则）
pnpm run convert
pnpm run fixtures

# 2. Web / Node 测试与数据校验
pnpm run validate
pnpm run amounts:check
pnpm exec vitest run

# 3. iOS CookCore 全量单元测试
swift test --package-path apps/ios/CookCore

# 4. Android 核心模块、本地库 Schema 与 Debug 构建验证
(cd apps/android && ./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug)
```

### 后续演进建议（防漂移加固）

- **规则常量外置**：后续可将推荐算法里的 `METHOD_GROUPS`、`DIFFICULTY_LEVELS`、`DEFAULT_SCORE` 等常量进一步提取为 `packages/data/rules.json`，让三端读取同一份规则定义而非各自保留字面量。
- **P3a 扩展 AI Fixtures**：移植 `SearchIntent` 与 `StepGuidance` 到 Android `:core` 时，复用 `step-guidance-numbers.json` 并补充 `search-intent-boundaries.json`。
