---
name: recipe-data-review
description: 定期审查和优化 Cook 的菜谱数据与筛选：汇总应用内反馈和筛选信号、处理菜谱 issue 和失效视频、补全缺失字段、按评测优化别名和排序，最后开一个 PR 交给维护者审核。维护者说"跑一下数据审查""处理菜谱反馈""优化菜谱数据/筛选"，或定时任务调用时使用。
metadata:
  author: YunYouJun
  repository: https://github.com/YunYouJun/cook
---

# 菜谱数据审查

你在维护 Cook（食用手册）的菜谱数据和筛选逻辑。每次运行产出**一个** PR，由维护者审核合并。你从不直接推送到 main，也从不合并 PR。

数据规则以 `docs/dev/data.md` 为准，开始前先读一遍。

## 硬性规则

- 数据只改 `app/data/recipe.csv`。需要新食材或厨具时，不要改 `app/data/food.ts`，写进 PR 描述的「需要维护者决定」部分。
- 列为 `name,stuff,bv,difficulty,tags,methods,tools,id`；单元格内多个值用顿号「、」分隔。
- **id 不能修改、不能复用。** 收藏、反馈、筛选信号都按 id 记录。新菜谱追加到末尾，id 用当前最大 id + 1。
- 不删除菜谱。重复或失效的菜写进「需要维护者决定」，由维护者决定是否删除（删除会让收藏它的用户丢失这一条）。
- 食材、厨具只能用 `app/data/food.ts` 里的名称。难度只能是 简单 / 普通 / 困难，或留空。
- **不编造视频号。** 替换视频时，必须用 `https://api.bilibili.com/x/web-interface/view?bvid=<BV号>` 确认 `code` 为 0，并且标题和菜名对得上；做不到就保留原值，写进 PR 描述。
- 筛选逻辑只允许改 `packages/cook/src/utils/alias.ts`（别名）和 `packages/cook/src/utils/recommend.ts`（排序），且必须有评测数据支撑（见第 6 步）。
- 拿不准的改动不做，写进 PR 描述。宁可少改，不要错改。

## 步骤

1. **准备分支**

   ```bash
   git fetch origin main && git checkout -B data/review-$(date +%F) origin/main
   pnpm install --frozen-lockfile
   ```

2. **收集输入**

   ```bash
   pnpm feedback:pull   # 应用内反馈和筛选信号；没配置 .env 就跳过这一步
   pnpm validate --json > /tmp/recipe-validate.json
   gh issue list --label recipe-data --state open --json number,title,body
   gh issue list --label dead-video --state open --json number,body
   ```

   读 `.cook/feedback/summary.md`。如果 `pnpm validate` 有错误，先修错误。

3. **处理应用内反馈**（summary 里的「菜谱反馈」，按分数从高到低）
   - 视频打不开：先用上面的接口确认确实失效，再按规则找替代视频。
   - 食材或厨具不对、难度或做法不对：对照视频信息核实后再改。
   - 只有 1 个人反馈且无法核实的，不改，列在 PR 描述里。

4. **处理 issue**
   - `recipe-data`：新增菜谱（视频可访问、食材能用词典描述、不和已有菜重复）或按 issue 纠错。信息不足就跳过，在 PR 描述里列出 issue 号和缺什么，不要在 issue 里发评论。
   - `dead-video`：能找到同一道菜的可用视频就替换 `bv`，找不到就列在 PR 描述里。

5. **补全缺失字段**，每次最多 20 行，挑最有把握的：
   - `methods`：菜名里明确写了做法才填（如「炸胡萝卜块」→ 炸）。
   - `difficulty`：只有看过视频信息（时长、步骤）能判断时才填，否则留空。
   - 食材明显漏了主料（菜名里有、`stuff` 里没有，且词典里有）时补上。

6. **优化筛选**（`test/fixtures/search-eval.json` 不为空时才做）

   ```bash
   pnpm eval:search --json > /tmp/eval-before.json
   ```
   - 「没有结果的食材组合」里，用户输入的词是已有食材的别名（如「羊排」之于「羊肉」）→ 在 `alias.ts` 加别名。词典里根本没有的食材 → 写进「需要维护者决定」。
   - 被频繁点开却排在 10 名以后的菜 → 可以调整 `recommend.ts` 的排序，但改动要小、要能解释。
   - 改完再跑一次，对比前后：

   ```bash
   pnpm eval:search --json > /tmp/eval-after.json
   ```

   命中率（hitAt10）或 MRR 下降、或 `pnpm test` 失败，就撤销这次筛选改动。

7. **验证**

   ```bash
   pnpm validate --base origin/main
   pnpm test -- --run
   ```

   `test/recommendations.test.ts` 里的数量断言会随数据变化。只有当变化是你的修改直接造成的，才更新这些数字，并在 PR 描述中说明。其他测试失败就停下，不要提交。

8. **提交并开 PR**
   ```bash
   git add app/data/recipe.csv test/fixtures/search-eval.json test/recommendations.test.ts packages/cook/src/utils/alias.ts packages/cook/src/utils/recommend.ts
   git commit -m "chore(data): recipe review $(date +%F)"
   git push -u origin HEAD
   gh pr create --title "chore(data): recipe review $(date +%F)" --body-file /tmp/recipe-review-pr.md
   ```
   没有任何改动就不开 PR，直接结束。

## PR 描述模板

```markdown
## 本次改动

| id  | 菜名 | 改动 | 依据（反馈 / issue / 视频信息） |
| --- | ---- | ---- | ------------------------------- |

## 筛选

- 别名 / 排序改动：……
- 评测：hitAt10 A → B，MRR A → B，仍无结果 A → B

## 需要维护者决定

- （新食材 / 新厨具、要删除的重复或失效菜谱、拿不准的改动、找不到替代视频的菜）

## 统计

- 校验：错误 0，警告 N
- 缺难度 X% → Y%，缺做法 X% → Y%

Closes #...
```

只在确实解决了的 issue 上写 `Closes`。
