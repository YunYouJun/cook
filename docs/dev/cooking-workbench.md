# Web 料理工作台试作

首页默认仍是经典模式，可用「经典模式 / 料理工作台」按钮切换。选择存放在同一个 `useRecipeStore`，切换不会清空食材、厨具或筛选。原生 iOS 不在本次范围内。

## 状态和匹配边界

- `useCookingStyle` 在 mounted 后读取 `cook:workbench-preferences`，保存界面样式和工作台明确确认过的调料；首次渲染固定为经典模式。损坏、非法值和禁止访问的存储不会阻止操作。
- 工作台调料从空列表开始，需要用户逐项确认，不继承经典模式默认的油、盐。单独保存这个清单是为了保留经典行为并避免把默认值当作用户确认。切换样式时清单不会丢失。
- `recommendWithPantry` 与原 `recipeMatches` 共用预处理后的 `createRecipeRecommender` 以及所有筛选参数；工作台只替换传入的 pantry。没有新配方、按摆放位置匹配的规则或不同的排序。
- 食材和厨具与经典模式共用个人词典，以共享 `food.json` 为基础叠加本机新增和隐藏偏好。分类、搜索和拖入校验即时使用同一份列表；隐藏或删除已选条目不会清空选择，食材仍可从砧板移除，当前厨具仍保留取消入口。菜谱仍来自已有 IndexedDB / 私人菜谱来源。没有食材记录的条目不会因为任意组合被推荐。仅选厨具时仍复用 `toolRecipes`，卡片标记「待核对」。
- 卡片分别展示匹配、缺料、明确确认的调料、杂烩可选材料和「未知：未记录的材料、调料与用量」。数据完整性无法由匹配算法保证；查看做法后再确认全部用料。

## 交互

食材和厨具使用带 `aria-pressed` 的原生按钮，Enter / Space 可操作。手机点选即可；桌面细指针支持 HTML5 拖放，重复拖入是幂等加入，外部文本及不在托盘中的拖拽数据会被忽略。食材顺序不参与推荐。

结果数量用简短 live region 宣读，卡片不整体作为 live region，避免每次选材读出整个列表。缺料有文字和符号。手机有直接跳到结果的按钮，并把焦点移到结果标题。减少动态效果时取消组件过渡及滚动动画。

## 验证入口

```sh
pnpm install --frozen-lockfile
pnpm convert
pnpm typecheck
pnpm exec vitest run
pnpm lint
pnpm -C apps/web generate
pnpm -C apps/web dev --host 127.0.0.1 --port 4317
```

重点回归：从经典页选番茄、鸡蛋、炒锅后双向切换；刷新样式偏好；工作台调料初始为空；搜索「面」检查缺料；搜索不存在菜名检查空态和放宽筛选；手机点选、结果跳转、键盘、桌面拖放、深色和减少动态效果。`test/cookingStyle.test.ts` 覆盖存储与 SSR，`test/workbenchRecommendations.test.ts` 覆盖共享引擎及明确确认调料的边界。
