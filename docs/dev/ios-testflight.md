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

2026-10-24 查询到现有 `2.0.0 (1)` 已在内部测试，候选构建 `2` 的 Xcode 导出创建了待上传记录，尚未分发。本轮整合 #133 的需求墙入口使用构建 `3`。每次发布都重新查询已处理和上传中构建，不能复用已占用的构建号。

## 资源与反馈配置

在最新 main 的独立工作树准备资源，生成工程前设置两个变量：

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
- 最新 main 还包含冰箱等既有功能，CloudKit 生产结构和双设备同步按 [P0 验收记录](./ios-p0-validation.md) 单独核对；模拟器和打包成功不代表生产同步已验证。
- 归档、dSYM、IPA、源码提交、哈希与脱敏发布回执保存在仓库外的版本化发布目录；清理临时 DerivedData、解包目录和本次临时模拟器。没有要求安装手机时不覆盖真机 App。

## 本轮内测的已知待验收项

维护者于 2026-10-24 明确同意将 CloudKit 登录与生产同步验收后置，不阻塞本轮反馈入口的 TestFlight 内测。测试说明必须标记「iCloud 同步待验收」；`CD_FridgeItem` 生产结构及双设备同步仍按 P0 记录跟进，不能记为通过。正式发布前补齐。#133 的实际分发构建复验也须单独记录，模拟器结果不能替代 TestFlight 真机验收。
