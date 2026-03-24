# @cook/types

共享类型定义包,用于在项目各模块间共享 TypeScript 类型。

## 导出的类型

- `Cookbook`: 菜谱集合
- `RecipeItem`, `Recipes`: 菜谱和菜谱列表
- `StuffItem`: 食材
- `IncompatibleRule`: 食物相克规则
- `Difficulty`: 难度类型

## 使用

```ts
import type { Cookbook, RecipeItem } from '@cook/types'
// 或者单独导入
import type { Cookbook } from '@cook/types/cookbook'
```
