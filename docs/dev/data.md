# 菜谱数据

## 数据流

```
飞书电子表格 ──pnpm fetch──▶ packages/data/recipe.csv ──pnpm convert──▶ recipe.json / 分片 ──▶ IndexedDB ──▶ 筛选
                                    ▲
          应用内反馈 / Issue / AI PR ┘   CI：pnpm validate     每周：pnpm check:videos
```

`packages/data/recipe.csv` 是构建时实际使用的数据。数据源以后放在哪里见 [方案记录 0001](/decisions/0001-recipe-data)。

## 数据规则

列为 `name,stuff,bv,difficulty,tags,methods,tools,id,link`。`link` 是可选的最后一列，没有图文做法的行可以只写到 `id`。

| 字段          | 规则                                                                                          |
| ------------- | --------------------------------------------------------------------------------------------- |
| name          | 必填                                                                                          |
| stuff         | 必填；只能使用 `packages/data/food.json` 里的食材名，别名（如「虾仁」）要写成标准名（「虾」） |
| bv            | B 站 BV 号或 av 号                                                                            |
| link          | 图文做法的网址，`https://` 开头；可以和 `bv` 同时存在，详情页会多一个「看图文做法」入口       |
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

错误会让 CI 失败：缺 id 或 id 重复、未知食材或厨具、分隔符错误、难度取值不对、视频号格式不对、图文链接不是 `https://` 网址。
警告只做提示：同名菜谱、完全重复的行（没有视频的按图文链接比较）。同时会统计缺难度、缺做法、视频和图文都没有的比例。

### 图文做法

每道菜至少要有视频或图文中的一个，详情页优先打开 B 站视频，两者都有时多一个「看图文做法」。目前的图文来自 [HowToCook](https://github.com/Anduin2017/HowToCook)（Unlicense），链接指向它在 GitHub 上的 Markdown 页面。网页点「看图文做法」跳转到这个链接；云食册内置 `packages/data/recipe-text.json` 里的正文，在 App 里离线显示，也可以打开原文。增删 HowToCook 的菜后重新生成正文：

```bash
git clone --depth 1 https://github.com/Anduin2017/HowToCook.git ../HowToCook
pnpm howtocook ../HowToCook
```

配图不进仓库：`pnpm howtocook` 把原文配图压成 webp 写到 `.cache/recipe-images`，合并到 main 后由 `Recipe Images` 工作流上传到 COS。桶、上传权限和换地址的做法见 [图文做法配图](/dev/recipe-images)。

导入规则和取舍见 [方案记录 0009](/decisions/0009-text-recipes)。

PR 上 CI 还会运行 `pnpm validate --base <目标分支>`：同一个 id 的菜名和视频都变了（像是把 id 挪给了另一道菜）或复用了已删除的 id 会报错，删除菜谱会给出警告。

想用新的食材，先在 `packages/data/food.json` 里加上，再在菜谱里使用。不常用的食材加上 `"more": true`，Web 和 App 的选择页会把它收进「更多」，避免挤占常用食材；已选中的食材始终显示。

## 反馈闭环

问题详情、来源版本及开发 / 预览隔离的字段和部署方式见 [0013](../decisions/0013-feedback-context.md)。

Web 和 iOS 菜谱纠错按实际来源提供“视频打不开”与“图文链接打不开”，分别提交 `kind: video` / `kind: text`；有两种来源时同时提供。新报告同时保存 `sourceBv` / `sourceLink`，便于审核时看到提交当时的链接。云函数校验来源格式，不请求这些链接。旧客户端省略来源字段时继续兼容；旧 `video` 数据可能是图文访问问题，后台按当前目录辅助识别，不自动改写历史反馈或审核状态。

1. **用户反馈**：菜谱详情点「反馈这道菜的问题」，或 Web 菜谱列表、收藏、历史记录中的「反馈」，确认自动携带的菜名、编号和做法链接，选择类型并补充说明后提交，不需要 GitHub，也不强制登录。数据进 CloudBase 的 `cook-feedback` 云函数；提交失败时保留完整内容，可前往支持中心、打开预填邮件或使用 GitHub Issue 表单（`.github/ISSUE_TEMPLATE/recipe.yml`，已预填菜名和视频）。
   「我的 → 问题反馈」是不针对某道菜的意见反馈：选类型（用着有问题、功能建议、想要的菜没有、其他），写几句，可以留邮箱或微信，同样不用登录，通过云函数复用 Support SDK 私密发送至统一支持后台，成功显示回执；失败保留草稿、复用提交标识重试并提供支持中心入口，也可选择预填邮件。「菜谱投稿」和「新功能建议」复用同一表单并预选类型。邮件需要用户自行确认发送，不会自动生成 Support 回执。渠道分流见 [0012](../decisions/0012-feedback-channels.md)。详见 [统一反馈决策](../decisions/0009-unified-support-feedback.md)。
   iOS App（云食册）没有 CloudBase SDK，用 `POST` 把同样的请求体发到云函数的 HTTP 访问地址，和 Web 共用设备限额和数据集合。三类请求都带 `platform`（`web` 或 `ios`）和 `version`（Web / iOS 应用版本号），没有 `platform` 的旧数据算作 web。筛选信号按端分开累计（Web 的记录键不变，iOS 的另起一组），`summary.md` 里菜谱反馈、意见反馈和筛选信号都按端分开统计；`search-eval.json` 把两端合在一起评测排序。
2. **筛选信号**：应用匿名记录两类聚合数据，只有食材组合、厨具、模式和菜谱 id，不含账号：
   - 停在一组食材上却没有任何结果（说明缺这类菜，或别名没对上）；
   - 在某组食材下点开了第几个结果（排得太靠后说明排序可以改进）。
3. **失效巡检**：`Check Videos` workflow 每周一调用 B 站接口检查全部视频，把失效列表写进带 `dead-video` 标签的 issue（已有就更新）。本地可运行 `pnpm check:videos`；加 `--stats` 会同时更新 `packages/data/video-stats.json`，再运行 `pnpm convert && pnpm fixtures` 就会刷新菜谱的质量分（同组菜按它排先后，见 [0010](../decisions/0010-recipe-quality-score)）。
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

1. 在 CloudBase 控制台新建四个集合，权限都设为「仅管理端可读写」：`cook_recipe_feedback`、`cook_search_signals`、`cook_feedback_quota`、`cook_app_feedback`（意见反馈）。
2. 更新现有云函数：先运行 `node scripts/prepare-support-feedback.mjs /absolute/task-temp/functions`，再进入生成目录运行 `tcb fn code update cook-feedback -e yunlefun-8g7ybcxc7345c490`。脚本校验私有 SDK 并安装依赖，部署配置关闭云端重复安装；不要直接部署源码目录。
3. 给 `cook-feedback` 设置环境变量 `COOK_FEEDBACK_EXPORT_TOKEN`（一串足够长的随机字符），并开启 HTTP 访问服务，得到一个访问地址。应用意见接入 Support 后，请使用 `node scripts/prepare-support-feedback.mjs /absolute/task-temp/functions` 准备带校验 SDK 的部署包，不直接部署源码目录。同一个地址有两种用法：带 `x-cook-token` 的 `GET` 导出全部数据；不带口令的 `POST`（JSON 请求体，`action` 为 `report`、`signal` 或 `feedback`）供 iOS App 提交，iOS 构建时把这个地址填进 `COOK_FEEDBACK_URL`。
4. 让未登录用户也能调用 `cook-feedback`：在控制台开启匿名登录或允许未登录访问该函数。否则只有登录用户能提交；失败时仍保留内容和支持中心、邮件等兜底入口。
5. 本机 `.env` 填 `COOK_FEEDBACK_URL`（第 3 步的地址）和 `COOK_FEEDBACK_TOKEN`（同一个口令），之后就能 `pnpm feedback:pull`。

云函数对每个设备（或账号）每天最多接受 200 次提交；同一个人对同一道菜的同一类问题每天只记一次，同一段意见反馈每天只记一次。意见反馈最多 500 字，联系方式最多 100 字，只出现在导出的 `export.json` 里，`summary.md` 只标记有没有留。

## 性能

筛选目前在内存里对全部菜谱逐条计算（`apps/web/app/composables/store/recipe.ts`），600 条约 1ms，上万条之内都不是瓶颈；首次加载在超过 1000 条后自动分片并按 hash 增量更新。数据量明显变大时，优先在 `convert` 阶段生成「食材 → 菜谱 id」倒排索引。

### 独立的用量与投料标注

`packages/data/recipe-amounts.json` 是正文以外的可选映射，结构见 `schema/recipe-amounts.schema.json`，不是新的菜谱 ID 来源。iOS 拷贝同一数据文件，`RecipeTextLibrary` 校验后附加给正文，`RecipePresentation`、`RecipeAmounts` 和 `RecipeStepUsagePlan` 实际读取；正文 `recipe-text.json` 不重写。旧包没有标注文件、schema 不支持、文件损坏或来源漂移时，应用继续展示原文与原有有限规则。

- `sourceFingerprint`：正文所有块的 FNV-1a 64 指纹，用于检测漂移，不是安全签名；`evidence` 逐条保存原块/列表项及完整原句。
- `servingEvidence` / `servingDescription`：记录原文份数口径；配方的一份可能够多人吃，不能擅自把它当作一人份。明确“按照 N 人的份量”时注明每人一份的换算口径，`referenceServings` 保留原配方基准，避免审查时把步骤原批次误判超量。
- `quantities`：封闭运算树，支持字面量、份数、材料引用、带单位的数量引用、乘除、同维度相加及向上/向下取整。没有明确依据的固定量保留固定。`conditional` 和原文说明必须保留，可选/条件量不参与无条件扣减；近似重量说明标记为“原文基准说明”，避免把原份的数字显示成当前份数的重量。
- `stepSections`：经核对的操作章节。既有步骤 ID 不变，新章节使用独立的 `annotated|` 身份前缀。
- `usages`：按原始块与列表项绑定步骤，种类为 `amount`、`total`、`remaining`、`unknown`、`reference`。`reference` 只标记有依据的切配、半成品转移或已投原料引用，不再次计入消耗。已写在步骤里的固定数值可仅加入账本（`display: false`）；计算提示保留原片段，再补充本步材料与实际量。相同文字的不同步骤也分别绑定，不按完成勾选扣料。
- `blockedIngredients`：分次、缩略指代、分支等关系不完整时，阻止把整菜总量自动填入单一步骤。前序为范围、可选、单位不兼容或超量时，不给确定余量。

复现全目录审查：

```bash
pnpm amounts:check       # schema、目录 ID、原文锚点和语义校验
pnpm audit:amounts       # 编译并调用生产 CookCore，生成中文及 JSON 全量报告
```

报告位于 `artifacts/recipe-amount-audit/`，以 `recipe.csv` 的实际 ID 集合为全集，逐块保留全部原文及定位。无正文条目全部列入缺少依据，不能从视频标题或食材词典推定份量。报告中的“至少一项可换算”不表示整菜已建立完整投料账本。

导入正文后标注失配会明确报出，CI 也执行 `amounts:check`。核对变化来源后，显式运行 `pnpm amounts:annotate` 再审阅差异；它从本地正文及生产 CookCore 原规则重建确定标注，不自动获取新来源，不在导入时重签原文。编译导出使用任务独占临时目录并在 `finally` 回收。

第二轮明确分配集中在 `scripts/lib/recipe-amount-followup.ts`，逐篇冻结来源指纹；整篇任一内容变化都会要求重新核对，不能在生成时自动重签。这些关系替换对应材料的阶段事件，未标注的额外使用点仍阻断余量。`pnpm audit:amounts` 同时生成 `pending-with-body.md/json`，供只处理有正文条目时使用；`followup-relations.json` 保存原句、定位、分配说明与完整性标记。条件范围的明确比例与“从前次不确定用量计算余量”分开处理，例如明确各半可保留两次相同比例范围，但不会代替用户确定实际取量。
