# 菜谱数据

## 数据流

```
飞书电子表格 ──pnpm fetch──▶ app/data/recipe.csv ──pnpm convert──▶ recipe.json / 分片 ──▶ IndexedDB ──▶ 筛选
                                    ▲
               Issue 表单 / PR / AI ─┘   CI：pnpm validate     每周：pnpm check:videos
```

`app/data/recipe.csv` 是构建时实际使用的数据。数据源以后放在哪里见 [方案记录 0001](/decisions/0001-recipe-data)。

## 数据规则

列为 `name,stuff,bv,difficulty,tags,methods,tools,`，行尾保留逗号。

| 字段          | 规则                                                                                   |
| ------------- | -------------------------------------------------------------------------------------- |
| name          | 必填                                                                                   |
| stuff         | 必填；只能使用 `app/data/food.ts` 里的食材名，别名（如「虾仁」）要写成标准名（「虾」） |
| bv            | B 站 BV 号或 av 号                                                                     |
| difficulty    | 简单 / 普通 / 困难，或留空                                                             |
| methods、tags | 自由填写                                                                               |
| tools         | 只能使用 `app/data/food.ts` 里的厨具名                                                 |

单元格内多个值用顿号「、」分隔，不要用逗号或斜杠。

**不要删除或调整已有行的顺序。** 收藏按行号记录（`app/utils/db.ts` 用行号作为 id，收藏存的是这个 id），删一行会让之后所有菜谱的收藏错位。新菜谱追加到末尾；重复或失效的菜谱先保留，等有了稳定 id 再清理。

## 校验

```bash
pnpm validate          # 有错误时退出码为 1，CI 中会在对应行标注
pnpm validate --json   # 输出 JSON，供 AI / 脚本使用
```

错误会让 CI 失败：未知食材或厨具、分隔符错误、难度取值不对、视频号格式不对。
警告只做提示：同名菜谱、完全重复的行。同时会统计缺难度、缺做法的比例。

想用新的食材，先在 `app/data/food.ts` 里加上，再在菜谱里使用。

## 反馈闭环

1. **用户反馈**：菜谱详情底部的「视频失效或信息不对？去反馈」打开预填好菜名和视频的 Issue 表单（`.github/ISSUE_TEMPLATE/recipe.yml`），自动带上 `recipe-data` 标签。
2. **失效巡检**：`Check Videos` workflow 每周一调用 B 站接口检查全部视频，把失效列表写进带 `dead-video` 标签的 issue（已有就更新）。本地可运行 `pnpm check:videos`。
3. **AI 处理**：维护者给 issue 打上 `ai-fix` 标签，`Recipe AI` workflow 让 Claude 按上面的规则修改 CSV、跑 `pnpm validate`，然后在 issue 里回复改动并附上 PR 链接。只有能打标签的人才能触发，PR 由人审核合并。需要在仓库 Secrets 中配置 `ANTHROPIC_API_KEY`。
4. **合并发布**：PR 合并后照常构建发布，`Closes #n` 自动关闭 issue。

## 性能

筛选目前在内存里对全部菜谱逐条计算（`app/composables/store/recipe.ts`），600 条约 1ms，上万条之内都不是瓶颈；首次加载在超过 1000 条后自动分片并按 hash 增量更新。数据量明显变大时，优先在 `convert` 阶段生成「食材 → 菜谱 id」倒排索引。
