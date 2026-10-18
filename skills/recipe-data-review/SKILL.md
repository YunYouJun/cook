---
name: recipe-data-review
description: 定期审查和优化 Cook 的菜谱数据（app/data/recipe.csv）：处理菜谱反馈 issue 和失效视频、补全缺失字段，最后开一个 PR 交给维护者审核。维护者说"跑一下数据审查""处理菜谱反馈""优化菜谱数据"，或定时任务调用时使用。
metadata:
  author: YunYouJun
  repository: https://github.com/YunYouJun/cook
---

# 菜谱数据审查

你在维护 Cook（食用手册）的菜谱数据。每次运行产出**一个** PR，由维护者审核合并。你从不直接推送到 main，也从不合并 PR。

数据规则以 `docs/dev/data.md` 为准，开始前先读一遍。

## 硬性规则

- 只改 `app/data/recipe.csv`。需要新食材或厨具时，不要改 `app/data/food.ts`，写进 PR 描述的「需要维护者决定」部分。
- **不删除、不调整已有行的顺序。** 收藏按行号记录，删一行会让后面所有菜谱的收藏错位。新菜谱追加到末尾。
- 列为 `name,stuff,bv,difficulty,tags,methods,tools,`，行尾保留逗号；单元格内多个值用顿号「、」分隔。
- 食材、厨具只能用 `app/data/food.ts` 里的名称。
- 难度只能是 简单 / 普通 / 困难，或留空。
- **不编造视频号。** 替换视频时，必须用 `https://api.bilibili.com/x/web-interface/view?bvid=<BV号>` 确认 `code` 为 0，并且标题和菜名对得上；做不到就保留原值，写进 PR 描述。
- 拿不准的改动不做，写进 PR 描述。宁可少改，不要错改。

## 步骤

1. **准备分支**

   ```bash
   git fetch origin main && git checkout -B data/review-$(date +%F) origin/main
   pnpm install --frozen-lockfile
   ```

2. **看现状**

   ```bash
   pnpm validate --json > /tmp/recipe-validate.json
   gh issue list --label recipe-data --state open --json number,title,body
   gh issue list --label dead-video --state open --json number,body
   ```

   如果 `pnpm validate` 有错误，先修错误。

3. **处理反馈 issue**（`recipe-data` 标签）
   - 新增菜谱：视频可访问、食材能用词典描述、不和已有菜重复（同名同视频）才追加。
   - 纠错：按 issue 修改对应行。
   - 信息不足就跳过，在 PR 描述里列出 issue 号和缺什么，不要在 issue 里发评论。

4. **处理失效视频**（`dead-video` issue 里的列表）
   - 能找到同一道菜的可用视频（按上面的规则确认）就替换 `bv`。
   - 找不到就保留，在 PR 描述中列出。

5. **补全缺失字段**，每次最多 20 行，挑最有把握的：
   - `methods`：菜名里明确写了做法才填（如「炸胡萝卜块」→ 炸，「清蒸鲈鱼」→ 蒸）。
   - `difficulty`：只有看过视频信息（时长、步骤）能判断时才填，否则留空。
   - 食材明显漏了主料（菜名里有、`stuff` 里没有，且词典里有）时补上。

6. **验证**

   ```bash
   pnpm validate
   pnpm test -- --run
   ```

   `test/recommendations.test.ts` 里的数量断言会随数据变化。只有当变化是你的数据修改直接造成的，才更新这些数字，并在 PR 描述中说明。其他测试失败就停下，不要提交。

7. **提交并开 PR**
   ```bash
   git add app/data/recipe.csv test/recommendations.test.ts
   git commit -m "chore(data): recipe review $(date +%F)"
   git push -u origin HEAD
   gh pr create --title "chore(data): recipe review $(date +%F)" --body-file /tmp/recipe-review-pr.md
   ```
   没有任何改动就不开 PR，直接结束。

## PR 描述模板

```markdown
## 本次改动

| 行  | 菜名 | 改动 | 依据 |
| --- | ---- | ---- | ---- |

## 需要维护者决定

- （新食材 / 新厨具、拿不准的改动、找不到替代视频的菜）

## 统计

- 校验：错误 0，警告 N
- 缺难度 X% → Y%，缺做法 X% → Y%

Closes #...
```

只在确实解决了的 issue 上写 `Closes`。
