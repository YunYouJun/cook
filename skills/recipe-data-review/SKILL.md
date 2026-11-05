---
name: recipe-data-review
description: 定期审查和优化 Cook 的菜谱数据与筛选：汇总应用内反馈和筛选信号、处理菜谱 issue 和失效视频、补全缺失字段、按评测优化别名和排序，最后开一个 PR 交给维护者审核。维护者说"跑一下数据审查""处理菜谱反馈""优化菜谱数据/筛选"，或定时任务调用时使用。
metadata:
  author: YunYouJun
  repository: https://github.com/YunYouJun/cook
---

# 菜谱数据审查

你在维护 Cook（食用手册）的菜谱数据和筛选逻辑。修订数据时产出**一个** PR，由维护者审核合并。你从不直接推送到 main，也从不合并 PR。只要求审查结论时，输出问题与依据，不修改数据或开 PR。

开始前读 [菜谱数据规范](../../docs/dev/data.md)。新增菜谱、补图文或替换视频都按其中「菜谱收录准则」核对，按「数据规则」写入；既有菜谱的缺陷只记录或有据纠正，不因新标准批量删除。

## 硬性规则

- 菜谱字段改 `packages/data/recipe.csv`；图文正文、共享用例等衍生文件按数据规范生成并审阅差异。需要新食材或厨具时，不要改 `packages/data/food.json`，写进 PR 描述的「需要维护者决定」部分。
- 列为 `name,stuff,bv,difficulty,tags,methods,tools,id,link`；单元格内多个值用顿号「、」分隔。来源至少有 B 站视频或 HowToCook 图文之一，纯图文不需要补视频。
- **id 不能修改、不能复用。** 收藏、反馈、筛选信号都按 id 记录。新菜谱追加到末尾，id 用当前最大 id + 1。
- 不删除菜谱。重复或失效的菜写进「需要维护者决定」，由维护者决定是否删除（删除会让收藏它的用户丢失这一条）。
- 食材、厨具只能用 `packages/data/food.json` 里的名称。难度只能是 简单 / 普通 / 困难，或留空。
- **不编造来源或核验结果。** 替换来源时核实菜品、主料、必要厨具和关键步骤匹配，不能只凭标题或 API `code: 0` 判定。视频复核遵守数据规范的范围和冷却规则；来源不可访问或证据不足时保留原值，写进 PR 描述，不认定为失效。
- 筛选逻辑只允许改 `packages/data/aliases.json`（别名）和 `packages/cook/src/utils/recommend.ts`（排序），且必须有评测数据支撑（见第 6 步）。
- 拿不准的改动不做，写进 PR 描述。宁可少改，不要错改。

## 步骤

1. **准备分支**

   先检查工作区和当前分支，保护未提交修改；需要隔离时用独立工作树。在合适的工作区从目标分支新建 `codex/recipe-review-<日期>`，不重置已有分支。依赖缺失时再运行 `pnpm install --frozen-lockfile`。

2. **收集输入**

   ```bash
   pnpm feedback:pull   # 应用内反馈和筛选信号；没配置 .env 就跳过这一步
   pnpm validate --json > /tmp/recipe-validate.json
   gh issue list --label recipe-data --state open --json number,title,body
   gh issue list --label dead-video --state open --json number,body
   ```

   读 `.cook/feedback/summary.md`。如果 `pnpm validate` 有错误，先修错误。

3. **处理应用内反馈**（summary 里的「菜谱反馈」，按分数从高到低）
   - 视频或图文打不开：先按数据规范核实对应来源；未知状态不当作失效。替代来源也须满足收录准则，确认与同一菜品匹配。
   - 食材或厨具不对、难度或做法不对：对照视频或图文教程核实后再改。
   - 只有 1 个人反馈且无法核实的，不改，列在 PR 描述里。
   - summary 里的「意见反馈」不是菜谱数据：「想要的菜没有」可以当作补菜线索（按 4 里新增菜谱的规则处理）；其他类型只在 PR 描述里简要列出，交给维护者。不要把反馈里的联系方式写进 PR 或 issue。

4. **处理 issue**
   - `recipe-data`：新增菜谱按收录准则给出「收录 / 暂缓 / 不收录」结论，已有同菜同做法优先补全；纠错需核实来源。信息不足就跳过，在 PR 描述里列出 issue 号和缺什么，不要在 issue 里发评论。
   - `dead-video`：核实原来源后，能找到满足准则且主料、必要厨具匹配的同一道菜视频就替换 `bv`，找不到就列在 PR 描述里。

5. **补全缺失字段**，每次最多 20 行，挑最有把握的：
   - `methods`：菜名里的做法可作为线索（如「炸胡萝卜块」→ 炸），对照教程核实后再填。
   - `difficulty`：根据实际步骤、操作要求判断；视频时长不等于难度，拿不准就留空。
   - 食材明显漏了主料（菜名里有、`stuff` 里没有，且词典里有）时，对照教程核实再补；不能省略必需主料来改善匹配率。

6. **优化筛选**（`test/fixtures/search-eval.json` 不为空时才做）

   ```bash
   pnpm eval:search --json > /tmp/eval-before.json
   ```
   - 「没有结果的食材组合」里，用户输入的词是已有食材的别名（如「羊排」之于「羊肉」）→ 在 `packages/data/aliases.json` 加别名。词典里根本没有的食材 → 写进「需要维护者决定」。
   - 被频繁点开却排在 10 名以后的菜 → 可以调整 `recommend.ts` 的排序，但改动要小、要能解释，并同步改 iOS 的 `apps/ios/CookCore/Sources/CookCore/Recommender.swift`（CI 的 swift 任务会用共享用例检查两边是否一致）。
   - 改完再跑一次，对比前后：

   ```bash
   pnpm eval:search --json > /tmp/eval-after.json
   ```

   命中率（hitAt10）或 MRR 下降、或 `pnpm test` 失败，就撤销这次筛选改动。

7. **验证**

   ```bash
   pnpm validate --base origin/main
   pnpm fixtures
   pnpm test -- --run
   ```

   `test/recommendations.test.ts` 里的数量断言会随数据变化。只有当变化是你的修改直接造成的，才更新这些数字，并在 PR 描述中说明。其他测试失败就停下，不要提交。`pnpm fixtures` 会重新生成 Web 和 iOS 共用的用例 `packages/data/fixtures/*.json`，一起提交。

8. **提交并开 PR**
   ```bash
   # 只暂存本轮实际修改并已审阅的文件，包括必要的衍生产物
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

## 来源筛选（新增 / 替换时填写）

| id / 候选菜名 | BV / 图文路径 | 作者 / 项目 | 核验日期与方式 | 主料 / 厨具依据 | 结论与理由 |
| --- | --- | --- | --- | --- | --- | --- |

记录通过条目的新增价值或替换理由；暂缓 / 不收录的候选写明缺少证据或未通过原因。

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
