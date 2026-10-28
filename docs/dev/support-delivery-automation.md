# Cook 修复关联与生产验收回写

Support 是工单状态、用户沟通和送达回执的权威来源。GitHub 只提供公开开发引用；Issue 按需关联，不自动创建 Issue，也不镜像 Discussion。

## 执行链路

`.github/workflows/support-sync.yml` 从受保护的 `main` 运行，在主分支提交、手动触发及每 15 分钟巡检时读取私密绑定。PR 无需合并即可关联；轮询只读取 PR 元数据，不执行 PR 源码或解释正文中的指令。合并时采用最终 merge/squash SHA，并通过 GitHub compare 核对它确实属于当前主分支。

1. 将 commit、PR 和可选 Issue 写入工单的 `changeRefs` 与内部处理记录；不因此解决或关闭工单。
2. 要求同一主分支 SHA 的 lint、typecheck、build、test 成功。
3. 查询真实 EdgeOne API，核实下面两个固定项目的当前生产部署为 `Success`、`UsedInProd`、`Production`、`Github`、`main`，且 SHA 与待验收版本一致。
4. 读取两个正式域名的 `/.well-known/cook-build.json`，核对完整 SHA、产品、仓库、版本及 `dirty: false`，再检查实际应用 HTML 的 `feedbackBuild` 和启动资源。浏览器执行绑定指定的验收流程，结束后重新核对部署指针。
5. 将真实部署及浏览器证据写入 `work.verification`，生成解决回复草稿。默认不发布回复。
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
    "resolutionReply": "经过核对的用户回复草稿",
    "sendReply": false
  }]
}
```

`issue` 可省略。`requesterMessageAt` 取真实工单快照，不猜测。新增用户消息、人工状态／草稿／验证变更和关联修订冲突均停止自动推进，交由维护者核对；不能靠增加 `revision` 丢弃结果未知的操作。

已实现的验收 profile：`app-smoke-v1` 验证真实选材与菜谱结果；`desktop-layout-v1` 另验证桌面侧栏、完整食材、常驻结果区、旧版入口、手机大按钮与结果抽屉。浏览器使用独立上下文并阻止生产写入。新的修复应添加与问题相符的 profile，不能仅凭首页 HTTP 200 宣称问题解决。

## 启用范围与凭据

先审查代码与配置，再建立或配置 GitHub Environment `support-cook`，分支策略仅允许 `main`。在该环境中保存：

- `COOK_SUPPORT_AGENT_JSON`：独立 Cook 工单代理的 `clientId`、`keyId`、`userId`、ES256 PEM `privateKey`；只使用已注册的 Cook 范围，不使用或升级 `feedback:submit` 凭据。
- `COOK_SUPPORT_BINDINGS_JSON`：上述私密映射。
- `COOK_SUPPORT_LEDGER_KEY`：独立随机密钥，至少 32 字节；与签名私钥分离。
- `EDGEONE_API_TOKEN`：有权只读核对这两个已有项目的 EdgeOne 凭据。

首次手动执行保持 `apply: false`、`send_replies: false`、`allow_initialize: true`，查看计数和等待阶段。确认后进行第一次 `apply: true` 执行，仍保持 `allow_initialize: true`；已有历史后不再允许初始化。完成验收后，设置仓库变量 `COOK_SUPPORT_ENABLED=true` 启用主分支和周期巡检。

回复可由维护者在 Support 页面核对并发布。若要由运行器发布，必须同时启用手动输入 `send_replies: true` 和对应绑定的 `sendReply: true`；草稿必须仍与本次验收生成的内容完全一致。定时巡检不会自动增加回复发送授权。

## 账本与重试

每个操作在发送前固定完整请求、版本、幂等键和守卫条件。未知结果重放相同输入和键；版本冲突只有在用户消息、人工处理和状态均未变化时才允许有限重基。生产动作的未知操作不能在单纯 commit／PR 关联阶段重放，仍须先通过本轮生产门槛。

账本使用独立密钥的 AES-256-GCM 加密，只有 `ledger.enc` 可上传到公开 Actions artifact，保留 90 天。下载时核对来源为本仓库 `main` 的 `support-sync.yml`，事件仅允许 push、schedule、workflow_dispatch；失败运行的账本仍可恢复，以保留未知意图。最新账本损坏、过期、来源不符、下载失败或密钥不符均停止，不回退旧账本，不自动重置。禁止把明文状态放入 Actions cache、artifact、job summary 或日志。

运行器仅输出阶段和数量。`blocked > 0` 表示需维护者核对；`awaiting_*` 表示尚不具备上线／关单条件。回滚、部署未就绪或浏览器回归失败时不处理送达关闭。

本机可使用同一入口预览：

```sh
node scripts/cook-support-sync.mjs --allow-initialize
```

环境变量使用 `COOK_SUPPORT_AGENT_CONFIG`、`COOK_SUPPORT_BINDINGS_FILE` 指向仓库外 0600 JSON，`COOK_SUPPORT_STATE_DIR` 指向 0700 私密目录；另设置加密密钥、只读 GitHub token、EdgeOne token 和绝对路径 `COOK_PLAYWRIGHT_MODULE`。只有显式 `--apply` 才执行工单命令。不要在命令或公开日志中写入凭据值。

## 停用与回退

设置 `COOK_SUPPORT_ENABLED=false` 停止自动巡检，保留账本、绑定和 Support 原始记录。回退发布按现有 EdgeOne Git 流程执行；自动化不会删除用户消息、伪造送达或回退人工状态。密钥轮换和账本恢复应在暂停巡检后核对，不能将恢复失败当作首次使用。
