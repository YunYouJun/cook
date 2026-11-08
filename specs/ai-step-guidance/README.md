# #87：iOS 已有菜谱 AI 逐步教学

用户于 2026-11-09 授权：按已讨论建议制定计划，新开 DeepSeek 会话实施；先做 iOS 已有菜谱的 AI 逐步教学，复用厨房工作台，CI 和测试保持精简。#174 在原会话继续收尾。此授权覆盖下列需求、设计和实施，不必逐阶段重复请求确认。

- Issue：https://github.com/YunYouJun/cook/issues/87
- 仓库：`/Users/yunyou/repos/github.com/YunYouJun/cook`
- 执行模型：`workbuddy/deepseek-v4.1-flash`
- 阅读顺序：[需求](requirements.md) → [设计](design.md) → [实施清单](tasks.md)。这些文档保留计划、实施状态和验收边界，不把未执行项记作通过。
- 如果上下文压缩后丢失任务，重新读本文件和实施清单，核对代码及进度后继续，不要求用户重述任务。

当前实现分支为 `codex/ios-ai-step-guidance`，基线为 `origin/main@b425e923c800ad953a0cdf2111c8f77edfa450f4`。源码、来源快照、定向测试和本地模拟器验证已完成；生产部署、真实付费模型调用、TestFlight 与真机 VoiceOver 仍未执行，不能写成已上线或已验收。

## 与 #174 协调

`#174` 原会话为 `01a1bc34-0e47-7c03-95ba-9cb1f66dd128`，hostId 为 `local`。规划时它正在审查并合并 #209；共享根目录在 `codex/dietary-goals-release-closeout`，另有工作树占用 main。不要依据这个快照直接切分支。

交接前最后一次快照：原会话已 idle，报告 #209 已合并为 `b425e923c800ad953a0cdf2111c8f77edfa450f4`、PR/main CI 通过，根工作区干净但仍停在已合并分支。生产函数部署、真机验收和候选 build 14 的发布仍未完成，继续留在原会话。执行时再次核对最新状态，即可在无其他写入的前提下开始自己的分支。

先用 `wait_threads` 的紧凑快照核对原会话，再检查 `git status`、`git worktree list` 和远端 main。原会话仍写入时先只读盘点，不切换、重置、清理它的工作目录，也不修改它的构建版本、候选包和发布结论。待其写操作结束且目录安全后，从最新 `origin/main` 新建 `codex/ios-ai-step-guidance`（已存在时先核对归属），不需要 checkout 被其他工作树占用的 main。若 #174 出现真正的共享写入阻塞，报告具体状态；不擅自创建额外工作树或覆盖修改。

本计划暂存在仓库外，避免原会话暂存/清理误收文件。取得自己分支后，将四份文档复制到 `specs/ai-step-guidance/` 并持续更新清单，后续以分支内版本为准。这里只保留小型任务输入；当提交版本及路径核验完成、任务结束后可回收这份外部副本。

## 已知基线与边界

- 厨房工作台已有原文步骤、备菜、份量换算、完成进度及计时器，见 `docs/dev/ios-kitchen-workbench.md` 与对应 validation 文档。
- `#181` 已有个人 DeepSeek Key 与会员托管 AI 搜索通道；搜索的 SearchIntent 合约不能直接充当教学合约。
- `docs/decisions/0007-ai-and-sentence-input.md` 原先限制 AI 只解析检索意图。本次明确授权新增 #87 教学能力，应添加有日期的范围扩展记录，保留原检索安全约束。
- `#174` 的生产兼容性、候选 build 和部署状态必须重新查证。本任务不动其发布候选，不复用其他任务的生产发布授权。
- 首版交付源码和可审查 PR。未部署的托管端、未执行的真实 Key 调用、真机 VoiceOver、TestFlight 等分别标明实际状态，不把模拟/离线验证写成真机或生产通过。#87 的其他平台及照片建议不随本里程碑自动完成。

## 执行纪律

遵守仓库及用户 AGENTS.md、适用技能；涉及 SwiftUI、CloudBase 或测试时按实际内容读取相应技能。Conventional Commits，分支使用 `codex/`。不新增 CI 矩阵或重复跑无关全量测试，不为文档变动重跑整套原生构建，也不削弱已有必需检查。

无需新开其他会话或子代理。不要自动部署生产函数、修改付费配置、上传 TestFlight、发起付费模型调用或自动合并/关闭 issue；如最后确需发布批准，先完成本地可审查结果并说明具体发布动作。不要在聊天、日志或仓库记录账号令牌及个人 Key。

模拟器先核对 `simctl list` 及 `~/Library/Developer/SimulatorMaintenance/shared-devices.json`，复用现有兼容设备；不克隆、清空或新建同款副本。`xcodebuild test` 显式 `-parallel-testing-enabled NO`。构建使用任务独占临时目录，复用依赖及缓存，回收本轮可重建中间产物并记录实际空间变化；保护在用设备、候选包、唯一证据及未提交修改。
