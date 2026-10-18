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
3. **AI 定期审查**：仓库里的 `recipe-data-review` skill（`skills/recipe-data-review/SKILL.md`）描述了完整流程：处理 `recipe-data` / `dead-video` issue、补全缺失字段、跑校验，最后开一个 PR。它不绑定模型，Codex、Claude Code 或接了 DeepSeek 的 Agent 都能跑，在本地按计划执行即可，见下方「本地定时运行」。
4. **合并发布**：维护者审核 PR，合并后照常构建发布，`Closes #n` 自动关闭 issue。

### 本地定时运行

skill 通过软链接暴露给不同 Agent：`.agents/skills/`（Codex）、`.claude/skills/`（Claude Code）、`.codebuddy/skills/`。需要本机装好并登录 `gh`。

```bash
# Codex
codex exec --full-auto "使用 recipe-data-review skill 做一次菜谱数据审查"

# DeepSeek：用支持自定义 API 地址的 Agent（如 Claude Code 按 DeepSeek 文档配置 ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN）
claude -p "使用 recipe-data-review skill 做一次菜谱数据审查" --permission-mode acceptEdits
```

每周跑一次即可，例如 crontab（周一 21:00，放在每周视频巡检之后）：

```cron
0 21 * * 1 cd ~/repos/cook && codex exec --full-auto "使用 recipe-data-review skill 做一次菜谱数据审查" >> ~/.cook-review.log 2>&1
```

Agent 只开 PR，不合并；没有改动时不开 PR。

## 性能

筛选目前在内存里对全部菜谱逐条计算（`app/composables/store/recipe.ts`），600 条约 1ms，上万条之内都不是瓶颈；首次加载在超过 1000 条后自动分片并按 hash 增量更新。数据量明显变大时，优先在 `convert` 阶段生成「食材 → 菜谱 id」倒排索引。
