# 步骤讲解合并与发布收尾

2026-11-11。关联 [PR #210](https://github.com/YunYouJun/cook/pull/210) 和 [#87](https://github.com/YunYouJun/cook/issues/87)。维护者已明确“Web 不接入模型，模型功能留给 App”，并授权完成合并、里程碑验收和 issue 收尾；#87 以 App 的可选模型与原文逐步教学为关闭范围，不含 Gemini、免费模型承诺或生成新菜谱／配图。

## 合并条件

- `main@a03e0266` 已合入，隐私政策冲突已解决。
- 数值校验修复的 `f30a63b4` 已通过全部必需 CI；规范与规格增量复核通过，无未解决的 GitHub 审查线程。
- 发布准备补充 `cloudbaserc.json` 的 `cook-ai-guidance` 单函数定义，并将 iOS 下一候选固定为 `2.0.0 (15)`。默认 `COOK_AI_GUIDANCE_ENABLED=false`，不把仓库默认值当作生产配置。
- 合并前以 PR 当前 head 的检查状态为准；发布准备后的提交也须通过必需检查。
- PR #210 已合并为 `773a777402893471629aed0326d9eb8530b0b081`。最终 head `fea1e5c7` 的[必需 CI](https://github.com/YunYouJun/cook/actions/runs/42885645361/attempts/2) 全部通过；规范／规格审查均通过，GitHub 未解决审查线程为 0。
- 最终 head 首轮的 StoreKit 超时和 iPad 同步确认弹层用例失败，经日志／画面核对后仅重跑失败任务，均原样通过；没有修改产品、断言、测试范围或时限，间歇失败根因尚未证明。生产教学已完成真实会员讲解验收并启用；内部构建和人工验收按下方实际状态记录。

## 首版验收与 #87 关闭范围

本次按维护者的最新收尾指令，采用已有定向测试、iPhone／iPad 模拟器闭环、一次真实生产会员讲解和构建 15 内部分发证据接受首版。Web「帮助」「关于」及官网补充 App 专属模型能力说明；说明合并并核验线上后关闭 #87。新增模型、免费服务、照片生成菜谱及配图是独立扩展，不作为本 issue 关闭前提，也不记作本次完成。

这项里程碑验收不代表维护者已确认每条模型回答事实，不代签个人 Key、真机 VoiceOver、断网或极端字号体验。以下人工待办已转入 [#216](https://github.com/YunYouJun/cook/issues/216) 继续跟踪；`internal-candidate.json` 的 `maintainerContentApproved=false`、`physicalChecksVerified=false`、`acceptancePolicy=pending-physical` 不变，不因此扩大到外部测试。当前公开邀请仍为构建 7。

## 生产核对与执行顺序

目标为现有国内上海环境 `yunlefun-8g7ybcxc7345c490`，沿用 `https://api.yunle.fun/cook-ai-guidance`，不新建域名或改变其他服务。

初次只读核对时生产不存在 `cook-ai-guidance`，地址 POST 返回 404；后续已完成关闭状态部署与真实会员 capability 验证。`cook-ai-intent` 为 `Nodejs24.11`、256 MiB、45 秒；其配置、修改时间和真实会员 capability 保持原值。本轮 MCP 登录工具超时后复用 CloudBase CLI 3.9.2，未修改本机全局 CLI 或登录配置。

新函数关闭状态部署时为 `Active`、`COOK_AI_GUIDANCE_ENABLED=false`；四个生产源文件已下载并逐字节核对，SDK 预打包且关闭云端安装。新增精确路径 `/cook-ai-guidance`，既有 `/`、`/fc-api` 路由保持原值。未登录／无效令牌为 401、GET 为 405；真实会员 capability 为 HTTP 200、会员有效且教学关闭，搜索 capability 仍启用。该阶段没有模型请求，见[关闭状态部署证据](../../docs/dev/evidence/step-guidance-disabled-deployment-2026-11-11.json)。

额度适用范围已核实。[官方计费说明](https://docs.cloudbase.net/ai/FAQ)及[资源点迁移公告](https://www.cloudbase.net/blog/2026/06/26/resource-point-token)明确资源点可抵扣模型 Token；生产回读为 `credits` 扣费模式、标准版 `NORMAL`、用量状态正常、费用状态 `positive`，有效包为 `pkg_tcb_credits_10000`（状态 0）。本机 AI 技能的旧 `pkg_tcb_tokencredits_*` 前缀门禁不适用于此资源点计费路径；未购买新包或切换通道。`cloudbase` 组及现用 `deepseek-v4.1-flash-1017` 均已启用。

临时开启新教学能力后，以既有受管生产会员完成一次真实 HTTP 讲解：返回 200、状态为 `answer`，请求绑定、当前步骤必引、来源白名单和完整数值校验通过；没有自动重试。验收后先恢复关闭，待构建 15 的内部可测状态核实后，再将独立开关启用并回读真实会员 capability。最终教学与搜索 capability 均启用，既有搜索配置及修改时间未变；两次测试会话均已注销，最终启用没有增加模型请求。接口不暴露 Token 用量，未编造计费 Token 数。见[真实请求与最终启用证据](../../docs/dev/evidence/step-guidance-live-acceptance-2026-11-11.json)。

1. **只部署新函数。** 使用已核验源码的四个文件和预打包 `@cloudbase/node-sdk@3.18.3`，关闭云端依赖安装，handler 为 `index.main`；不执行 `--all`，不覆盖现有搜索函数。
2. **保留共同配额。** 从现有生产搜索函数读取并复用 `COOK_AI_QUOTA_SALT`、用户日／分钟限制和全服务日预算，沿用同一个 `cook_ai_quota`。不生成新摘要密钥，不重置计数，不修改会员或测试账号权益。平台模型变量沿用生产当前值，显式校验所选通道需要的凭据。
3. **先关闭再挂载。** 新函数保持 `COOK_AI_GUIDANCE_ENABLED=false`，新增精确路径 `/cook-ai-guidance`，由函数内校验账号与会员。CloudBase CLI 的便捷路由命令未产生有效写入，回读确认后改用文档化的 `CreateHTTPServiceRoute`；已核对新路径及既有路由，不替换根路径 `/`、不迁移共享 API、不修改环境授权策略。
4. **验收后开启。** 确认未登录／无效令牌为 401、GET 为 405；真实会员 capability 在关闭状态下报告不可用。确认生产模型可用及适用额度后，仅将新函数的独立开关设为 `true`。使用现有受管会员身份完成一次真实 HTTP 讲解，核对请求绑定、来源、当前步骤必引、完整数值和共享配额配置，仅保留脱敏回执。失败时关闭独立开关，不自动重试或切换付费通道。CLI 配置更新需要显式非交互选项；不能把退出码 0 或文本输出当作配置已写入的证据，须回读全部原有变量及唯一开关差异。
5. **兼容与回退。** 再核对搜索 capability 与旧契约仍可用。回退优先关闭新函数开关；新路径发生接入问题时仅撤销新路径。保留现有函数、网关、模型配置、旧客户端契约和已分发构建。

函数包不含生产凭据；生产变量通过既有授权的运行时配置注入。发布回读须记录代码散列、Active 状态、运行时／内存／超时／依赖安装配置和实际接入地址。源码或本地替身测试不替代这些结果。

## TestFlight 内部分发

准备前 Apple 只读查询的已处理和上传中最高构建号均为 14，因此冻结构建 15。Xcode 导出随后创建了构建 15 的 `AWAITING_UPLOAD` 分析会话；上传前再次查询该精确会话，只有 `ASSET_SPI` 和 `ASSET_DESCRIPTION`，没有 App 二进制。当时实际已处理构建仍为 14，下一构建号查询因分析会话返回 16；没有据此盲目重传、自动更换候选构建号或把分析会话当作分发。

随后已上传同一散列的 IPA，实际 App 上传／构建 ID 为 `384e4392-8703-4b68-a21b-0eaa54c6d1dc`。共享服务端 `ready` 与 `published` 门禁均通过：`VALID / INTERNAL_ONLY / IN_BETA_TESTING`、外部 `NOT_APPLICABLE`、未过期、无外部组。既有内部组自动关联并保留 1 名成员；已审查精确组关联预演，未重复写入。中文测试说明已写入并回读一致，Apple 仅移除末尾换行。首次 `published` 复核中的单条只读 ASC 查询失败，核对同一 App 与组后完整复查通过，没有重传 IPA。见[分发证据](../../docs/dev/evidence/step-guidance-build15-published-2026-11-11.json)。

- 候选源码：`674d3b5f`，包含已通过回归的 `f30a63b4`；新增内容仅为函数默认配置和构建号。
- 目标：App `6824589542`、Bundle `fun.yunle.cook`、`2.0.0 (15)`，既有内部组 `a2962c09-0f3d-4180-abef-030cf8920279`。
- 从独立工作树归档，正式签名并仅导出内部测试 IPA。签名含 Production 推送、CloudKit／CloudDocuments 与项目声明的 Associated Domains，关闭调试权限。校验与产物散列见[准备证据](../../docs/dev/evidence/step-guidance-release-preparation-2026-11-11.json)。
- `scripts/testflight/internal-candidate.json` 已在构建 15 真正发布并回读通过后更新；构建 14 的历史分发证据保留。外测构建 7 及 30 人邀请保持原有记录。
- 上传必须使用已通过共享验包和 Apple 包校验的同一 IPA。上传后按精确版本／构建号等待处理；`server --phase ready` 通过后预演既有内部组关联，已自动关联则不重复写入；最后执行 `server --phase published` 并回读中文测试说明。
- 测试说明应包含步骤讲解入口、原文引用、需主动发送、会员与个人 Key 的适用条件，以及下面的未验证范围；不声称生产模型具备任意事实正确性。

## 仍需实际完成的验收

| 项目                         | 当前状态         | 完成条件                                                     |
| ---------------------------- | ---------------- | ------------------------------------------------------------ |
| 新函数、网关、关闭状态鉴权   | 已完成           | 配置、代码、既有路由、拒绝路径和真实会员 capability 已回读   |
| 模型额度、开关、真实会员讲解 | 已完成           | 资源点计费核实、独立服务启用、真实 HTTP 200 及脱敏回执已记录 |
| 个人 DeepSeek Key            | 未实调           | 由 Key 的持有人在候选中主动调用；不要求在聊天中提供 Key      |
| TestFlight 内部分发          | 已完成           | 构建 15 已通过共享 `published` 门禁，中文说明一致、无外部组  |
| 真机 VoiceOver 与断网恢复    | 未验证           | 实际朗读、焦点遍历、离线操作和恢复核对                       |
| 最大辅助字号                 | 模拟器可滚动触达 | 人工核对固定进度／计时栏与长回答，保留已知覆盖现象           |

真机 VoiceOver 听读不能由标签、截图或自动化断言代签。上述未验证项目作为首版之后的人工验收待办继续跟踪；#87 按上文确认的功能范围收尾，不把这些检查记作通过。
