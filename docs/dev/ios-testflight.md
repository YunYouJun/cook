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

2026-10-24 的发布记录：`2.0.0 (1)` 是早期内测，构建 `2` 的 Xcode 导出曾创建待上传记录；整合 #133 需求墙入口的 `2.0.0 (3)` 随后已完成内部组分发、iPhone 实际安装与反馈流程验收，[#133](https://github.com/YunYouJun/cook/issues/133) 已于当日 14:24（UTC+8）关闭。该历史验收不包含 CloudKit 同步。

后续包含私人菜谱、个人词典和偏好同步修复的 `2.0.0 (4)` 已完成 TestFlight 内部发布，固定源码 `6e4343faa523f400a6d2eeb0380223e80d632f95`（含 #137 布局修复）。该候选的 [CI](https://github.com/YunYouJun/cook/actions/runs/40079188284) 全部通过后，[#136](https://github.com/YunYouJun/cook/pull/136) 已合并为 main `e090866474fe5f589c0ac01d36892b802dc33b62`；合并时两者源码树相同。后续主线提交不自动扩大冻结发布范围。服务端发布状态已按精确构建回读，详情见下方验收记录；构建 `4` 的单机升级、数据保留与偏好持久化结果同样见下方记录，不能推导为生产同步通过。每次上传前重新查询已处理和上传中构建，不能复用已占用的构建号。

## 2026-10-29 饮食目标引导内测

`2.0.0 (11)` 已按精确构建回读为 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，Build ID 为 `2108cd6c-fec2-45f0-bfbf-7e6019783674`，沿用既有内部组与成员。冻结源码 `7e1e6e175d618c35824d789aced330d01ecbcf1d` 整合 main `75d50ad`、布局 `fe6086a` 与既有会员 AI；7 个关键布局／状态源文件与实际回归的版本逐字一致。IPA SHA-256 为 `bb1e6ab961fb0c9c760c556675ca6faf16abf4a283e709bee8350e6e2200cb9e`，同一文件通过签名、配置、隐私资源、dSYM／Production 权限与 Apple 包校验后上传。

首次引导在选择、完成或跳过后收起；后续从筛选或更多菜单修改，仍由用户主动开启目标记忆。17 项原生测试、iPhone 8 条功能／字号流程及固定产物后的 iPad 3 条回归通过；iPad 首轮宿主终止及重验范围如实记录在[验证记录](../../specs/dietary-goals/validation)。中文测试说明已写入并回读。维护者继续在真机比较布局与会员功能，发布状态不替代真机或实际 VoiceOver 朗读验收。产物与回执保存在 `~/Library/Developer/ReleaseArtifacts/Cook/20261029-2.0.0-11-goal-intro`，临时构建与完整诊断副本按任务独占范围回收。

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
