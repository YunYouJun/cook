# 云食册 TestFlight 内部发布

原生工程为 `apps/ios/Cook.xcodeproj`，Scheme 为 `Cook`，由 XcodeGen 从 `apps/ios/project.yml` 生成。使用共享 [ios-testflight 流程](https://github.com/YunYouJun/ai/blob/main/skills/ios-testflight/SKILL.md) 分步归档、导出、核验、上传和确认内部组分发。

| 参数         | 值                                                                        |
| ------------ | ------------------------------------------------------------------------- |
| App ID       | `6824589542`                                                              |
| Bundle ID    | `fun.yunle.cook`                                                          |
| Team         | `QT792GAY4C`                                                              |
| 内部组       | `内部测试` / `a2962c09-0f3d-4180-abef-030cf8920279`                       |
| 版本来源     | `apps/ios/project.yml` 的 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` |
| 导出配置     | `apps/ios/ExportOptions-TestFlight.plist`                                 |
| 隐私清单     | `apps/ios/Cook/Resources/PrivacyInfo.xcprivacy`                           |
| 正式反馈地址 | `https://api.yunle.fun/cook-feedback`                                     |

## 2026-11-11 步骤讲解构建 15 准备

`2.0.0 (15)` 已从 PR #210 的冻结提交 `674d3b5f` 完成归档、仅内部导出、共享签名／隐私清单验包、正式资源核对和 Apple 包校验，**尚未上传或分发**。IPA SHA-256 为 `57a9b7a0bda547dbef5fd0eaf92f78aad6945ee64231dcd31cc8ef3b2e42ed64`。产物与回执保存在 `~/Library/Developer/ReleaseArtifacts/Cook/20261111-step-guidance-15`，精确状态见[准备证据](./evidence/step-guidance-release-preparation-2026-11-11.json)。

当前实际内部候选仍为构建 14；不更新其发布记录或外测构建 7。上传前再次核对已处理和上传中构建号，生产步骤讲解独立 capability 及真实会员请求按[发布收尾](../../specs/ai-step-guidance/release-closeout.md)执行。个人 Key、真人 VoiceOver／焦点顺序、断网恢复与极端字号人工核对仍未验证，测试说明草稿保留这些边界。

## 2026-11-09 饮食目标构建 14 内部发布

维护者已认可本版内容，明确授权构建 14 内部上传，并确认「按已有模拟器证据收尾，真机项目保留未验证」。PR #211 的最终 16 项检查通过、1 项按策略跳过后，合并为 `8e0a3267`；合并源码树与通过 CI 的 head 一致。

原已验包的 `2.0.0 (14)` IPA 仅上传一次，SHA-256 仍为 `fe7aed5916dd899eaf187773376625c0fdb3a8adbda054dc218f1d7fe90c0dfa`，冻结源码 `1b456042`。精确 Build ID 为 `d0fe231d-350f-4dd6-9df6-8e98bb468a0e`，共享服务端核验回读 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`、外部 `NOT_APPLICABLE`，未过期。既有「内部测试」组自动关联，沿用原有 1 名成员，未重复发起组关联；预演已核对精确组 ID，没有外部组关联。中文测试说明已写入并回读一致，Apple 仅移除末尾换行。

生产智能找菜契约已兼容当前 `353bc8dab0ea` 和两个保留版本；本轮没有部署或计费模型调用。构建内置 887 道目录、36 道饮食审核和 10 道机构原方，复核说明和原有模拟器证据继续保留。真人 VoiceOver、真实飞行模式／网络恢复和真机冷启动未验证，不因维护者采用模拟器范围而改写为通过；本轮未安装或更新手机，也未更改外测构建 7、其 30 人限额或公开邀请。

[分发证据](./evidence/dietary-build14-published-2026-11-09.json)记录维护者授权与服务器验收摘要；完整私密回执保存在 `~/Library/Developer/ReleaseArtifacts/Cook/20261109-dietary-readiness`。原归档、dSYM、IPA 和源码快照继续保留在当日构建 14 发布目录。下方是关闭前及早间准备阶段的历史状态。

## 2026-11-09 关闭前复核

生产 `cook-ai-intent` 的 5 个源文件已下载并核对与 main `b425e923` 逐字节一致，支持 `353bc8dab0ea` 以及 `faf919fc0041`、`1878cc5772dc` 两个保留版本；早间记录的生产契约阻塞已解除，本轮无需重复部署。构建 14 的原 IPA 重新通过共享验包，11 个内置内容文件逐字节一致，元数据仅重建时间戳不同。仍只有 `AWAITING_UPLOAD` 分析会话，没有已处理的构建 14；最新实际内测构建仍为 12。

构建 7 的外部组仍开放且上限 30 人，外测配置继续绑定该构建；构建 14 改由 `scripts/testflight/internal-candidate.json` 单独记录，内部组为本页参数表中的既有组。当前未取得新上传授权，未修改 Apple 状态。内容批准、人工使用验收与关闭条件见[复核材料](https://github.com/YunYouJun/cook/blob/main/specs/dietary-goals/release-review.md)，[当前证据](./evidence/dietary-readiness-2026-11-09.json)优先于下方早间快照。

## 2026-11-09 饮食目标候选 14（未上传）

本节保留当日早间准备候选时的状态；后续生产契约回查以本页“关闭前复核”为准。

2026-11-09 只读回查 ASC：实际已处理最新构建仍是 `2.0.0 (12)`，Build ID `315742ed-3c67-48d3-907b-ed88dad320d4`，状态 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，外部 `NOT_APPLICABLE`，未过期。构建 13 仍只有 `AWAITING_UPLOAD` 分析会话 `330c89c1-715d-4a28-9161-4af5fa76a2c5`，文件为 `ASSET_SPI` 与 `ASSET_DESCRIPTION`；ASC 下一可用构建号复核为 14，因此本轮不复活或覆盖构建 13。

已从源码提交 `1b456042` 准备 `2.0.0 (14)` Release 归档和仅内部测试 IPA。IPA SHA-256 为 `fe7aed5916dd899eaf187773376625c0fdb3a8adbda054dc218f1d7fe90c0dfa`，通过共享验包脚本确认正式 Bundle ID、Team、仅内部标记、分发签名、App Store 描述文件和关闭调试权限；同一文件通过 Apple `altool --validate-app`，结果为无错误。归档包内 887 道目录、数据版本 `353bc8dab0ea`、饮食标注 36 道、机构低 GI 原方 10 道，审核与共享数据文件同仓库逐字节一致；完整契约可用包内数据重建为 `353bc8dab0ea`。会员 UI／购买和微信登录开关均为 `NO`，正式反馈／AI 地址、Production 推送与 iCloud 权限、隐私清单和 App／dSYM UUID `B2950AFB-2F14-3595-B7AA-8CB6145CF78A` 一致。

首次导出使用 PATH 中的 Homebrew rsync，因 `--extended-attributes` 不被其服务端接受而报 `Copy failed`；仅在本次发布子进程把系统 `/usr/bin` 放到 PATH 前面后重试成功，未修改全局环境。`reviewReady=true`，但 `distributionReady=false`：生产 `cook-ai-intent` 仍为 `1878cc5772dc`，兼容快照只有 `faf919fc0041` 与 `1878cc5772dc`，必须先部署支持 `353bc8dab0ea` 的函数；此外真人 VoiceOver、飞行模式验收和维护者内容批准仍未完成。本轮未上传、未分发、未通知测试员，也未修改手机或生产函数。

归档、IPA、dSYM、源码快照、验包与 Apple 校验回执、数据对比和定向 UI 结果保存在 `~/Library/Developer/ReleaseArtifacts/Cook/20261109-2.0.0-14-dietary-closeout`。脱敏摘要见[发布收尾证据](./evidence/dietary-release-closeout-2026-11-09.json)。

2026-10-24 的发布记录：`2.0.0 (1)` 是早期内测，构建 `2` 的 Xcode 导出曾创建待上传记录；整合 #133 需求墙入口的 `2.0.0 (3)` 随后已完成内部组分发、iPhone 实际安装与反馈流程验收，[#133](https://github.com/YunYouJun/cook/issues/133) 已于当日 14:24（UTC+8）关闭。该历史验收不包含 CloudKit 同步。

后续包含私人菜谱、个人词典和偏好同步修复的 `2.0.0 (4)` 已完成 TestFlight 内部发布，固定源码 `6e4343faa523f400a6d2eeb0380223e80d632f95`（含 #137 布局修复）。该候选的 [CI](https://github.com/YunYouJun/cook/actions/runs/40079188284) 全部通过后，[#136](https://github.com/YunYouJun/cook/pull/136) 已合并为 main `e090866474fe5f589c0ac01d36892b802dc33b62`；合并时两者源码树相同。后续主线提交不自动扩大冻结发布范围。服务端发布状态已按精确构建回读，详情见下方验收记录；构建 `4` 的单机升级、数据保留与偏好持久化结果同样见下方记录，不能推导为生产同步通过。每次上传前重新查询已处理和上传中构建，不能复用已占用的构建号。

## 2026-11-01 找菜视图菜单内测

`2.0.0 (12)` 已按精确构建回读为 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，Build ID 为 `315742ed-3c67-48d3-907b-ed88dad320d4`。既有「内部测试」组自动关联，沿用原成员；外部状态为 `NOT_APPLICABLE`，没有外部组。中文测试说明已写入并回读一致。

找菜保留首屏食材快选，通过标题栏「视图」菜单切换云厨房；随机和三道灵感合入找菜页，场景移到筛选，输入框收紧。同步优化 iPad 双栏连续点菜、最大辅助字号的菜单标记、44pt 入口点击范围及食材篮。主线已有的会员 AI 显式确认与饮食目标默认保存继续保留。

实际归档源码为 `dc20115605244c68c925063f8ce76d231ebeeb92`，基于 main `0c1757b1f2b4951d1f23e6715954c356e3b3cc08`。209 项原生测试、iPhone 六条及 iPad 三条关键 UI 流程完成；首轮一条 UI 的滚动定位失败及测试提交 `d7c3a2e1` 后的通过结果，详见[发布回归](./dinner-discovery.md)。该测试修正未改变产品源码、资源或最终 IPA。

IPA SHA-256 为 `1c6d8de3ad8cce96f1265eb6e0c00c5ffe6d6ecb0b0c5bb6f2a8fdd8250af30b`。同一文件通过分发签名、内部限制、Production 权限、正式反馈／AI 地址、877 道菜、隐私资源、App 与 dSYM UUID 及 Apple 包校验后上传。Xcode 本地导出产生的同构建分析会话仅含分析资源，已核对没有 App 二进制；实际 IPA 只上传一次，没有盲目重传或修改分发范围。

归档、dSYM、IPA、冻结源码、导出配置及脱敏回执保存在 `~/Library/Developer/ReleaseArtifacts/Cook/20261031-2.0.0-12-view-menu`；目录日期为开始准备的日期。构建／测试中间产物按 `du` 计回收 608.6 MiB，可用空间实测增加 583.8 MiB。共享 iPad 已恢复 Shutdown，未新建或擦除模拟器。服务端分发验收完成；本轮未操作维护者手机，真机体验由维护者从 TestFlight 更新后进行。

## 2026-10-29 饮食目标引导内测

`2.0.0 (11)` 已按精确构建回读为 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，Build ID 为 `2108cd6c-fec2-45f0-bfbf-7e6019783674`，沿用既有内部组与成员。冻结源码 `7e1e6e175d618c35824d789aced330d01ecbcf1d` 整合 main `75d50ad`、布局 `fe6086a` 与既有会员 AI；7 个关键布局／状态源文件与实际回归的版本逐字一致。IPA SHA-256 为 `bb1e6ab961fb0c9c760c556675ca6faf16abf4a283e709bee8350e6e2200cb9e`，同一文件通过签名、配置、隐私资源、dSYM／Production 权限与 Apple 包校验后上传。

首次引导在选择、完成或跳过后收起；后续从筛选或更多菜单修改，仍由用户主动开启目标记忆。17 项原生测试、iPhone 8 条功能／字号流程及固定产物后的 iPad 3 条回归通过；iPad 首轮宿主终止及重验范围如实记录在[验证记录](https://github.com/YunYouJun/cook/blob/main/specs/dietary-goals/validation.md)。中文测试说明已写入并回读。维护者继续在真机比较布局与会员功能，发布状态不替代真机或实际 VoiceOver 朗读验收。产物与回执保存在 `~/Library/Developer/ReleaseArtifacts/Cook/20261029-2.0.0-11-goal-intro`，临时构建与完整诊断副本按任务独占范围回收。

## 2026-10-29 会员 AI 内测

`2.0.0 (10)` 已按精确构建回读为 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，Build ID 为 `c31e2a32-7955-44cd-8a95-f5c013637d2c`。沿用既有内部组与 1 名成员，没有关联外部组。冻结源码为 `7c183f00f1c18e72cd314a1c8fee863d638f6056`，整合 main `620b495` 与 AI 分支 `8c94644`；IPA SHA-256 为 `1200ac9f18949083cacea4ab89e11b99e461d2a1f49edaf153ac025390cd5075`。会员服务地址、正式反馈地址、数据契约、隐私清单与正文、分发签名、dSYM UUID、CloudKit／推送 Production 权限及内部标记均核验通过，同一散列 IPA 的 Apple 包校验通过后上传。中文测试说明已回读一致，真机会员操作由维护者执行。

本轮自动导出选中了缺少 Associated Domains 的旧描述文件，并尝试使用当前 Key 无权访问的云端分发证书。已在任务独立的导出配置副本中改为手动签名，复用本机已有的 Apple Distribution 身份和支持 Associated Domains 的 App Store 描述文件，保持 `testFlightInternalTestingOnly=true`、`manageAppVersionAndBuildNumber=false` 和 Production 环境。以后遇到相同错误，先核对现有证书私钥可用、描述文件有效期和能力，不要回退旧权限或直接复用外测配置。

该主机还出现系统 rsync 子进程从 PATH 选中 Homebrew rsync，后者拒绝 `--extended-attributes` 而使导出报 `Copy failed`；仅在本轮发布子进程中优先系统工具路径后导出成功，没有修改全局 PATH。归档、dSYM、IPA、源码副本、导出配置与脱敏回执保存在 `~/Library/Developer/ReleaseArtifacts/Cook/20261029-2.0.0-10-member-ai`；临时构建和解包目录在发布结束时回收。

## 资源与反馈配置

在本次冻结候选或经确认源码等价提交的独立工作树准备资源，生成工程前设置两个变量：

```bash
pnpm install --frozen-lockfile
pnpm convert
export APPLE_DEVELOPMENT_TEAM=QT792GAY4C
export COOK_FEEDBACK_URL=https://api.yunle.fun/cook-feedback
xcodegen generate --spec apps/ios/project.yml
```

发布前核对归档和最终 IPA 中的 `CookFeedbackURL` 与表中地址完全一致，不能把无配置的降级入口当成应用内反馈验收通过。不要把 CloudBase 导出令牌、Support 私钥或飞书凭据写入 Info.plist。

「我的 → 意见反馈」通过既有 `cook-feedback` HTTP 协议提交，由云函数复用 Support SDK 进入统一私密队列；「需求墙」直达 `/feedback?product=cook`，独立的「支持中心」跳转保留。菜谱纠错、搜索信号与应用意见按 action 分流，不重复写入。反馈管理地址为 `https://support.yunle.fun/manage?product=cook`；飞书主动通知尚未启用。

## 分发验收

- 使用 Release、正式 Bundle ID 和仅内部测试的导出配置。检查 IPA 签名、描述文件、隐私清单、关闭调试权限，以及 CloudKit `Production` 和推送 `production` 权限。
- 完成 Apple 包校验后上传同一散列的 IPA，等待精确版本和构建号 `VALID`。沿用已有内部组与成员，确认 `INTERNAL_ONLY / IN_BETA_TESTING`、外部 `NOT_APPLICABLE`，不启用外测。
- 实测意见反馈入口、发送成功、失败保留内容、支持中心跳转，以及后台仅团队可见。保留测试回执并清理明确的验收数据。
- 发布候选包含冰箱和私人菜谱在内的五个持久模型。生产结构、真实生产读写和双设备同步按 [P0 验收记录](./ios-p0-validation.md) 及[私人菜谱门禁](./private-recipes.md)分别核对；结构匹配、模拟器和打包成功不代表真实生产同步已验证。
- 归档、dSYM、IPA、源码提交、哈希与脱敏发布回执保存在仓库外的版本化发布目录；清理临时 DerivedData、解包目录和本次临时模拟器。没有要求安装手机时不覆盖真机 App。

## 2026-10-24 验收状态与发布门禁

本节保留构建 3 阶段的历史状态。最新真实生产结果见[构建 6 验收](./ios-production-sync-2026-10-24.md)；维护者已决定剩余项目改用[模拟器验收](./ios-simulator-acceptance-2026-10-24.md)，不再要求真机。不要将下面“没有第二台设备”等历史描述当成当前状态。

以下记录截至当日 17:50（UTC+8）；后续构建 6 的内部发布、两端原位升级及离线冲突通过证据见[真实生产同步验收](./ios-production-sync-2026-10-24.md)。下方“没有第二台设备”等描述仅为历史状态，不能自动扩大冻结发布范围。

- 已完成：构建 `3` 的实际分发包已在 iPhone 验收需求墙、支持中心、投撤票和私密反馈，测试数据已清理；#133 已关闭。
- 已完成：构建 `4`（buildId `d575e25b-c0b0-4548-8571-f929cdcfda90`）服务端回读为 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，已关联既有「内部测试」组（1名成员），无外部分发；`zh-Hans` 测试说明已更新并回读，保留 iCloud 同步待验收标记。
- 已完成：构建 `4` 通过 TestFlight 在 iPhone 15 Pro / iOS 27.0.1 原位升级并正常启动，无卸载或清库；收藏、历史、冰箱和偏好与本地升级前基线一致。
- 已完成：仅修改一项偏好，分别确认 Cook 进程实际退出后重新启动，开启与恢复关闭均保留，其他偏好不变且设置已恢复。这只证明单机持久化，不证明 CloudKit 传输。
- 仍待完成：私人菜谱与个人词典的真机文件往返验收；模拟器和 Web–Swift 往返结果不替代此项。
- 已完成：生产结构补齐 `CD_FridgeItem` 与 `CD_PrivateRecipeRecord`，五模型的 Development/Production 完整导出匹配，详情见 P0 记录。此结果不包含真实 CloudKit 传输。
- 仍待完成：同一修复构建的真实生产读写、前台自动刷新、双设备同步、私人菜谱离线冲突、账号隔离和退出 iCloud 后的数据保留。目前没有第二台设备，双设备项目保持待完成；两端须使用包含偏好修复的同一候选构建，不能与旧构建 `3` 混测后宣称通过。
- 测试说明继续标记「iCloud 同步待验收」，正式发布前完成剩余门禁。构建 `4` 及后续构建的源码整合、CI 与分发包验收均须独立记录，不能沿用构建 `3` 的结果覆盖新功能；模拟器结果不替代 TestFlight 真机结果。
