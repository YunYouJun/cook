# CookCore

iOS App 的核心逻辑（Swift Package）：读取 `packages/data` 的数据，推荐、搜索、相克判断，移植自 `packages/cook` 的 TS 实现。背景见 [0003 原生 iOS App 与 monorepo 结构](../../../docs/decisions/0003-native-ios-monorepo.md)。

```bash
# 在仓库根目录
pnpm convert                                   # 生成 recipe.json、recipe-meta.json
swift test --package-path apps/ios/CookCore    # 跑和 Web 共用的用例
```

- 两端的推荐结果（包括顺序）由 `packages/data/fixtures/*.json` 保证一致。改推荐、搜索或相克规则时两边一起改，再运行 `pnpm fixtures`。
- 数据结构变化（字段改名、删除、改类型）时，同时加 `packages/cook` 的 `DATA_SCHEMA_VERSION` 和这里的 `CookData.supportedSchemaVersion`，并更新 `packages/data/schema`。
