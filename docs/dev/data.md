# 菜谱数据

## 数据流

```
飞书电子表格 ──pnpm fetch──▶ packages/data/recipe.csv ──pnpm convert──▶ recipe.json / 分片 ──▶ IndexedDB ──▶ 筛选
                                    ▲
          应用内反馈 / Issue / AI PR ┘   CI：pnpm validate     每周：pnpm check:videos
```

`packages/data/recipe.csv` 是构建时实际使用的数据。数据源以后放在哪里见 [方案记录 0001](/decisions/0001-recipe-data)。

## 数据规则

列为 `name,stuff,bv,difficulty,tags,methods,tools,id`。

| 字段          | 规则                                                                                          |
| ------------- | --------------------------------------------------------------------------------------------- |
| name          | 必填                                                                                          |
| stuff         | 必填；只能使用 `packages/data/food.json` 里的食材名，别名（如「虾仁」）要写成标准名（「虾」） |
| bv            | B 站 BV 号或 av 号                                                                            |
| difficulty    | 简单 / 普通 / 困难，或留空                                                                    |
| methods、tags | 自由填写                                                                                      |
| tools         | 只能使用 `packages/data/food.json` 里的厨具名                                                 |
| id            | 必填，非负整数且唯一；新菜谱用当前最大 id + 1                                                 |

单元格内多个值用顿号「、」分隔，不要用逗号或斜杠。

**id 一旦发布就不能修改或复用。** 收藏、用户反馈和筛选信号都按 id 记录（`apps/web/app/utils/db.ts` 用它作 IndexedDB 主键）。可以改菜名、换视频，也可以调整行的顺序；删除一道菜只会让收藏了它的用户丢失这一条，其他菜不受影响。被删掉的 id 不要再给新菜用。

## 校验

```bash
pnpm validate          # 有错误时退出码为 1，CI 中会在对应行标注
pnpm validate --json   # 输出 JSON，供 AI / 脚本使用
```

错误会让 CI 失败：缺 id 或 id 重复、未知食材或厨具、分隔符错误、难度取值不对、视频号格式不对。
警告只做提示：同名菜谱、完全重复的行。同时会统计缺难度、缺做法的比例。

PR 上 CI 还会运行 `pnpm validate --base <目标分支>`：同一个 id 的菜名和视频都变了（像是把 id 挪给了另一道菜）或复用了已删除的 id 会报错，删除菜谱会给出警告。

想用新的食材，先在 `packages/data/food.json` 里加上，再在菜谱里使用。

## 反馈闭环

1. **用户反馈**：菜谱详情底部点「反馈一下」，选问题类型（视频打不开、食材或厨具不对、难度或做法不对、其他）即可提交，不需要 GitHub，也不强制登录。数据进 CloudBase 的 `cook-feedback` 云函数；提交失败时引导用户去 GitHub Issue 表单（`.github/ISSUE_TEMPLATE/recipe.yml`，已预填菜名和视频）。
2. **筛选信号**：应用匿名记录两类聚合数据，只有食材组合、厨具、模式和菜谱 id，不含账号：
   - 停在一组食材上却没有任何结果（说明缺这类菜，或别名没对上）；
   - 在某组食材下点开了第几个结果（排得太靠后说明排序可以改进）。
3. **失效巡检**：`Check Videos` workflow 每周一调用 B 站接口检查全部视频，把失效列表写进带 `dead-video` 标签的 issue（已有就更新）。本地可运行 `pnpm check:videos`。
4. **AI 定期审查**：仓库里的 `recipe-data-review` skill（`skills/recipe-data-review/SKILL.md`）描述了完整流程：拉取应用内反馈和筛选信号、处理 `recipe-data` / `dead-video` issue、补全缺失字段、按评测结果优化筛选，跑校验，最后开一个 PR。它不绑定模型，Codex、Claude Code 或接了 DeepSeek 的 Agent 都能跑，在本地按计划执行即可，见下方「本地定时运行」。
5. **按主题补充**：`recipe-sourcing` skill（`skills/recipe-sourcing/SKILL.md`）描述了从 B 站、小红书等平台按主题搜罗菜谱的流程：网页搜索找 BV 号、逐个打开视频页核对、按数据规则追加并开 PR。
6. **合并发布**：维护者审核 PR，合并后照常构建发布，`Closes #n` 自动关闭 issue。

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

### 拉取反馈与筛选评测

```bash
pnpm feedback:pull   # 汇总到 .cook/feedback/summary.md，并更新 test/fixtures/search-eval.json
pnpm eval:search     # 用真实信号评估当前排序：前 10 命中率、MRR、仍无结果的组合
```

`test/fixtures/search-eval.json` 是匿名聚合后的评测样例，会提交到仓库。AI 修改 `packages/data/aliases.json`（别名）或 `recommend.ts`（排序）时，必须在 PR 中写明修改前后的 `pnpm eval:search --json` 指标，且 `pnpm test` 通过。

### Web 和 iOS 共用的用例

`packages/data/fixtures/` 记录了一组输入和当前 TS 实现给出的结果：`recommend.json`（推荐，菜谱 id、顺序和缺少的食材）、`search.json`（搜索）、`incompatible.json`（相克判断）。Web 的 `test/fixtures.test.ts` 和 iOS 的 CookCore（`swift test --package-path apps/ios/CookCore`，CI 的 swift 任务）都要和它完全一致。

改了 `recipe.csv`、`aliases.json`、推荐或搜索逻辑后运行 `pnpm fixtures` 重新生成，并在 PR 里看结果的变化是否符合预期；忘了运行时 `pnpm test` 会失败。用例的输入在 `scripts/lib/fixtures.ts`。逻辑改动要同时改 `apps/ios/CookCore` 里对应的 Swift 代码。

整句识别（`parseCookQuery`）的词表在 `packages/data/query.json`，用例 `fixtures/query.json` 是手写的，不由 `pnpm fixtures` 生成：改词表或规则时手动补用例，并核对期望结果。背景见 [0007](/decisions/0007-ai-and-sentence-input)。

### 数据版本和结构

`pnpm convert` 生成的 `recipe-meta.json` 带两个版本号：

- `schemaVersion`：JSON 的结构版本，即 `packages/cook` 的 `DATA_SCHEMA_VERSION`。字段改名、删除或改类型时加一，同时更新 `packages/data/schema/` 和 CookCore 的 `CookData.supportedSchemaVersion`；只加可选字段不用改。App 遇到不认识的版本会继续用内置数据。
- `dataVersion`：菜谱、相克规则、食材词典、常备调料和别名的内容 hash，内容不变则不变。iOS 按它判断 CDN 上的数据是否比内置的新。

每个 JSON 的结构在 `packages/data/schema/*.schema.json`，`test/data-schema.test.ts` 会检查数据是否符合。

### CloudBase 配置（一次性）

1. 在 CloudBase 控制台新建三个集合，权限都设为「仅管理端可读写」：`cook_recipe_feedback`、`cook_search_signals`、`cook_feedback_quota`。
2. 部署云函数：`tcb fn deploy cook-feedback`（配置见 `cloudbaserc.json`）。
3. 给 `cook-feedback` 设置环境变量 `COOK_FEEDBACK_EXPORT_TOKEN`（一串足够长的随机字符），并开启 HTTP 访问服务，得到一个访问地址。
4. 让未登录用户也能调用 `cook-feedback`：在控制台开启匿名登录或允许未登录访问该函数。否则只有登录用户能提交，未登录用户会被引导去 GitHub。
5. 本机 `.env` 填 `COOK_FEEDBACK_URL`（第 3 步的地址）和 `COOK_FEEDBACK_TOKEN`（同一个口令），之后就能 `pnpm feedback:pull`。

云函数对每个设备（或账号）每天最多接受 200 次提交；同一个人对同一道菜的同一类问题每天只记一次。

## 性能

筛选目前在内存里对全部菜谱逐条计算（`apps/web/app/composables/store/recipe.ts`），600 条约 1ms，上万条之内都不是瓶颈；首次加载在超过 1000 条后自动分片并按 hash 增量更新。数据量明显变大时，优先在 `convert` 阶段生成「食材 → 菜谱 id」倒排索引。
