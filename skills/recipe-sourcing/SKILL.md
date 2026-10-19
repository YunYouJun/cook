---
name: recipe-sourcing
description: 按主题从 B 站、小红书等平台搜罗菜谱，补充进 Cook 的 recipe.csv 并开 PR。维护者说"补充 XX 类菜谱""去 B 站/小红书找菜谱""某个食材没菜可做"时使用。
metadata:
  author: YunYouJun
  repository: https://github.com/YunYouJun/cook
---

# 菜谱搜罗

你在给 Cook（食用手册）按主题（某种食材、某种厨具、某个菜系）补充菜谱。每次运行产出**一个** PR，由维护者审核合并。你从不直接推送到 main，也从不合并 PR。

数据规则以 `docs/dev/data.md` 为准，开始前先读一遍。

## 硬性规则

- 每道菜必须有一个**可播放的 B 站视频**（`bv` 列）。小红书、下厨房等只能作为找菜名、核对食材的参考，不进 csv。
- **不编造视频号。** 每个 BV 号都要打开视频页（`https://www.bilibili.com/video/<BV号>/`）或调 `https://api.bilibili.com/x/web-interface/view?bvid=<BV号>`（`code` 为 0）确认：视频没失效，标题和菜名对得上。只在搜索结果里见过、没打开确认的不要用。
- 食材、厨具只能用 `packages/data/food.json` 里的名称。具体品种写成标准名（鲈鱼 → 鱼、虾仁 → 虾），对应别名加进 `packages/data/aliases.json`。
- `stuff` 只填视频标题、简介或标签里能看出来的主料，看不出来的配菜不填。`difficulty` 拿不准就留空。
- **id 不能修改、不能复用。** 新菜谱追加到末尾，id 用当前最大 id + 1。
- 不和已有菜重复：同一个视频、或同名同做法的菜不加。
- 需要新食材或新厨具时，写进 PR 描述的「需要维护者决定」，不要自己加进 `packages/data/food.json`（维护者明确要求的除外）。

## 步骤

1. **准备分支**

   ```bash
   git fetch origin main && git checkout -B data/sourcing-<主题> origin/main
   pnpm install --frozen-lockfile
   ```

2. **看现状**：在 `packages/data/recipe.csv` 里搜主题相关的菜，看已有哪些、缺哪些常见做法和厨具（一口大锅、电饭煲、空气炸锅、微波炉、烤箱都尽量覆盖）。

3. **找候选视频**
   - B 站搜索页（search.bilibili.com）和小红书一般禁止抓取，直接访问多半失败。可行的做法是用网页搜索，关键词带上 `bilibili.com/video` 和菜名，例如 `bilibili.com/video 红烧鱼 家常做法`、`bilibili.com/video 空气炸锅 秋刀鱼`，从结果里拿 BV 号。
   - 小红书能访问时，用来确认哪些菜做的人多，再去 B 站找对应视频。
   - 同一道菜优先选播放量高、做法清楚的家常视频（如「美食作家王刚R」「小高姐的魔法调料」这类长期更新的 UP 主）。
   - 每个主题 10～20 道就够，宁缺毋滥。

4. **逐个核对**：打开每个候选视频页，记下标题、UP 主、播放量、简介和标签里的食材与厨具，确认没有失效提示。对不上的丢掉。

5. **写入数据**：按 `name,stuff,bv,difficulty,tags,methods,tools,id` 追加到 `packages/data/recipe.csv`，单元格内多个值用顿号「、」分隔。

6. **验证**

   ```bash
   pnpm validate --base origin/main
   pnpm fixtures
   pnpm test -- --run
   pnpm lint
   ```

   `test/recommendations.test.ts` 里的数量断言会随数据变化，只在变化是你新增的菜直接造成时才更新。`pnpm fixtures` 重新生成的 `packages/data/fixtures/*.json` 一起提交。

7. **提交并开 PR**：PR 描述列出每道新菜的 id、菜名、BV 号、UP 主和视频标题，方便维护者抽查；说明哪些平台没能访问。

## PR 描述模板

```markdown
## 新增菜谱

| id  | 菜名 | 视频 | UP 主 | 视频标题 |
| --- | ---- | ---- | ----- | -------- |

## 别名 / 词典

- ……

## 来源说明

- 能访问的平台、找不到视频的菜

## 需要维护者决定

- ……
```
