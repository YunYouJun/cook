# Cook 修复关联与生产验收回写

Support 是工单状态、用户沟通和送达回执的权威来源。GitHub 只提供公开开发引用；Issue 按需关联，不自动创建 Issue，也不镜像 Discussion。

## 执行链路

`.github/workflows/support-sync.yml` 从 `main` 运行，生产凭据只由限定 `main` 的 `support-cook` 环境提供，在主分支提交、手动触发及每 15 分钟巡检时读取私密绑定。PR 无需合并即可关联；轮询只读取 PR 元数据，不执行 PR 源码或解释正文中的指令。合并时采用最终 merge/squash SHA，并通过 GitHub compare 核对它确实属于当前主分支。

1. 将 commit、PR 和可选 Issue 写入工单的 `changeRefs` 与内部处理记录；不因此解决或关闭工单。
2. 锁定同一主分支 SHA 对应的 `ci.yml` 最新 main push 运行及当前 attempt，要求其中全部 lint、typecheck、build、test（含所有矩阵任务）成功；必须同时通过依赖这四项的 `web-acceptance` 聚合任务，以证明整个矩阵均已结束；其他工作流的同名 job 和旧运行结果不能替代。
3. 查询真实 EdgeOne API，核实下面两个固定项目的当前生产部署为 `Success`、`UsedInProd`、`Production`、`Github`、`main`，且 SHA 与待验收版本一致。
4. 读取两个正式域名的 `/.well-known/cook-build.json`，核对完整 SHA、产品、仓库、版本及 `dirty: false`，再检查实际应用 HTML 的 `feedbackBuild` 和启动资源。浏览器执行绑定指定的验收流程，结束后重新核对部署指针。
5. 将真实部署及浏览器证据写入 `work.verification`，根据经过核对的修复事实和实际通过的验收项目生成解决回复；默认保存草稿，获得发送授权后自动发布。
6. 对本次自动化草稿发布产生的解决回复推进 `resolved`；仅匹配这条回复的真实用户端 portal `delivered` / `read` 回执才能推进 `closed`。公开到工单页的 `available` 和微信通知回执均不能代替此条件。

| 项目                 | 正式域名                    | 应用验收路径              |
| -------------------- | --------------------------- | ------------------------- |
| `pages-d3ggorpihuk6` | `https://cook.yunyoujun.cn` | `/`                       |
| `pages-izqbljwk91wt` | `https://cook.yunle.fun`    | `/home`，同时检查产品首页 |

GitHub 中现有 `Production – cook` 部署记录来自 Vercel，不作为 EdgeOne 上线证据。tag Release 也不更新这两个正式站点。

## 私密绑定

绑定只保存在受保护环境 Secret 或仓库外的 0600 文件中；公开 PR 不包含私密工单链接、原文、联系方式或访客访问凭据。多个工单可以绑定同一 PR。

```json
{
  "schemaVersion": 1,
  "tickets": [{
    "ticketId": "SYNTHETIC_TICKET_ID",
    "repository": "YunYouJun/cook",
    "pullRequests": [123],
    "commitShas": [],
    "issue": 100,
    "requesterMessageAt": "2026-10-28T00:00:00Z",
    "revision": 1,
    "acceptanceProfile": "desktop-layout-v1",
    "fixSummary": {
      "changes": ["优化电脑端导航和食材筛选，增加旧版网页入口"],
      "userAction": "请刷新网页后体验；如果仍有不便，欢迎继续补充。"
    },
    "sendReply": false
  }]
}
```

`fixSummary` 由维护者或执行修复的代理在核对代码后记录。运行器用这些事实和实际通过的验收 profile 确定性组合回复，不读取 PR 正文或用户原文进行推断，也不需要额外模型凭据。缺少修复事实或对应验收证明时不生成成功回复、不自动发送。特殊工单可用经过核对的 `resolutionReply` 完整覆盖文案。

`issue` 可省略。`requesterMessageAt` 取真实工单快照，不猜测。新增用户消息、人工状态／草稿／验证变更和关联修订冲突均停止自动推进，交由维护者核对；不能靠增加 `revision` 丢弃结果未知的操作。

已实现的验收 profile：`app-smoke-v1` 验证真实选材与菜谱结果；`desktop-layout-v1` 另验证桌面侧栏、完整食材、常驻结果区、旧版入口、手机大按钮与结果抽屉。浏览器使用独立上下文并阻止生产写入。新的修复应添加与问题相符的 profile，不能仅凭首页 HTTP 200 宣称问题解决。仅重跑失败任务时，当前 CI attempt 可能缺少先前成功的 Web job；此时门槛会停止，需要全量重跑 CI，不能借用旧 attempt 的结果。

## 启用范围与凭据

先审查代码与配置，再建立或配置 GitHub Environment `support-cook`，分支策略仅允许 `main`。在该环境中保存：

- `COOK_SUPPORT_AGENT_JSON`：独立 Cook 工单代理的 `clientId`、`keyId`、`userId`、ES256 PEM `privateKey`；只使用已注册的 Cook 范围，不使用或升级 `feedback:submit` 凭据。
- `COOK_SUPPORT_BINDINGS_JSON`：上述私密映射。
- `COOK_SUPPORT_LEDGER_KEY`：独立随机密钥，至少 32 字节；与签名私钥分离。
- `EDGEONE_API_TOKEN`：有权只读核对这两个已有项目的 EdgeOne 凭据。

首次手动执行保持 `apply: false`、`send_replies: false`、`allow_initialize: true`，查看计数和等待阶段。确认后进行第一次 `apply: true` 执行，仍保持 `allow_initialize: true`；已有历史后不再允许初始化。完成验收后，设置仓库变量 `COOK_SUPPORT_ENABLED=true` 启用主分支和周期巡检。

回复可由维护者在 Support 页面核对并发布。自动发送要求对应绑定的 `sendReply: true`，并满足以下运行授权之一：手动执行时明确选择 `send_replies: true`；主分支和定时巡检时设置仓库变量 `COOK_SUPPORT_AUTO_REPLY=true`。默认不开启，手动执行始终以本次输入为准。每次未知结果重试都会重新检查两层授权和回复策略；撤销授权或修改修复事实、文案、验收 profile 后保留原意图并停止发送。生成的草稿及验证记录必须仍与本次验收一致。

## 账本与重试

每个操作在发送前固定完整请求、版本、幂等键和守卫条件。未知结果重放相同输入和键；版本冲突只有在用户消息、人工处理和状态均未变化时才允许有限重基。生产动作的未知操作不能在单纯 commit／PR 关联阶段重放，仍须先通过本轮生产门槛。

账本使用独立密钥的 AES-256-GCM 加密。每个新命令执行前，先通过固定版本的官方 `@actions/artifact` 完成远端不可变检查点上传；收到 finalized ID 并核对摘要后才发送命令。runner 在命令成功后被终止，也能恢复原输入与幂等键。状态未变化的巡检不重复上传，仅每日刷新保留期。检查点名称只包含 run、attempt 和序号，不包含工单身份；只上传加密文件，保留 90 天。

每次最多写入 9 个命令检查点，并为最终状态预留一个；批次耗尽时保存待执行意图，下一轮继续。同一 run 的早期 attempt 也属于恢复历史。下载使用官方工具包，核对来源为本仓库 `main` 的 `support-sync.yml`、摘要和单文件；事件仅允许 push、schedule、workflow_dispatch。失败运行的检查点仍可恢复。最新检查点损坏、过期、来源不符、下载失败或密钥不符均停止，不回退旧状态，不自动重置。工具包日志在隔离子进程内处理，禁止把明文状态放入 Actions cache、artifact、job summary 或日志。

运行器仅输出阶段和数量。`blocked > 0` 表示需维护者核对；`awaiting_*` 表示尚不具备上线／关单条件。回滚、部署未就绪或浏览器回归失败时不处理送达关闭。

本机可使用同一入口预览：

```sh
node scripts/cook-support-sync.mjs --allow-initialize
```

GitHub 运行使用 Node action 获取官方 artifact 运行时；浏览器只在需要新的生产验收时安装，已验收版本的送达巡检复用既有验收证明。CI 会用独立的合成加密数据执行真实 artifact 上传、下载与摘要验收，不访问 Support，不使用工单检查点命名空间。

环境变量使用 `COOK_SUPPORT_AGENT_CONFIG`、`COOK_SUPPORT_BINDINGS_FILE` 指向仓库外 0600 JSON，`COOK_SUPPORT_STATE_DIR` 指向 0700 私密目录；另设置加密密钥、只读 GitHub token、EdgeOne token 和绝对路径 `COOK_PLAYWRIGHT_MODULE`。只有显式 `--apply` 才执行工单命令。不要在命令或公开日志中写入凭据值。

## 停用与回退

设置 `COOK_SUPPORT_AUTO_REPLY=false` 停止后续发送；设置 `COOK_SUPPORT_ENABLED=false` 停止自动巡检，保留账本、绑定和 Support 原始记录。回退发布按现有 EdgeOne Git 流程执行；自动化不会删除用户消息、伪造送达或回退人工状态。密钥轮换和账本恢复应在暂停巡检后核对，不能将恢复失败当作首次使用。
