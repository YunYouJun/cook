# 云食册 TestFlight 外部测试准备

手动提交审核、30 人限额、验收门禁及私密反馈汇总见 [外测自动化](./testflight-automation.md)。

本页用于外测，现有 [内部发布流程](./ios-testflight.md) 继续使用原配置。2026-10-24 准备的首个外测候选为 **2.0.0 (5)**，基于 main `9bdbcf25dc6ef4f0774d81873021b38fbd6b2507`，包含 #138 的厨具界面改进。候选源码 `8a12512ccf6e1437eae8c99cee8b028621fafdfe` 只增加构建号与独立导出配置，应用逻辑与该 main 相同。

2026-10-24 已上传并回读为 `VALID / APP_STORE_ELIGIBLE / READY_FOR_BETA_SUBMISSION`，尚未提交 Beta 审核或向普通用户开放。每次后续上传前重新查询构建号，确认未被其他发布占用。

## 构建与导出

- App ID：`6824589542`；Bundle ID：`fun.yunle.cook`；Team：`QT792GAY4C`。
- 工程：`apps/ios/Cook.xcodeproj`；Scheme：`Cook`；版本来源：`apps/ios/project.yml`。
- 外测导出配置：`apps/ios/ExportOptions-ExternalTestFlight.plist`，`method=app-store-connect`、`destination=export`、`testFlightInternalTestingOnly=false`、`manageAppVersionAndBuildNumber=false`。
- 原 `ExportOptions-TestFlight.plist` 保持仅内部测试。不要修改已签名 IPA 的 Info.plist 来转换用途。
- 生成工程前设置 `APPLE_DEVELOPMENT_TEAM=QT792GAY4C`、`COOK_FEEDBACK_URL=https://api.yunle.fun/cook-feedback`，其余资源准备、归档及凭据处理沿用内部流程。
- 导出后核对唯一 App、版本/构建号、App Store 分发签名、描述文件、无调试权限、隐私清单与政策正文、反馈 URL、全部菜谱资源，以及 CloudKit `Production` / APS `production`。`TFInternalTestingOnly` 必须为 false 或不存在。
- 通用 `ios-testflight` 技能的验证器强制检查 `INTERNAL_ONLY`，不能用于证明外测包合格。外测单独检查包内标志，上传后回读 `buildAudienceType=APP_STORE_ELIGIBLE`，并核对正确 App、版本、构建号、`VALID`、未过期与外测状态。
- 通过 Apple `altool --validate-app` 后才上传同一 SHA-256 的 IPA。归档、dSYM、源码快照、生成资源、散列与回执放在仓库外，清理本轮 DerivedData 和验包临时目录。

## 提交给 Apple 的材料

### Beta App Description（简体中文）

云食册是「食用手册」的原生 iPhone / iPad 应用。根据家里的食材和厨具寻找菜谱，查看图文做法和视频来源，收藏常做的菜，记录冰箱食材与到期日，也可以保存自己的菜谱、管理个人食材和厨具。

核心功能无需注册账号，选食材、搜索和推荐在设备上完成。配图、在线视频和反馈需要网络。iOS 与 Web 可通过文件手动迁移数据。测试版主要收集找菜体验、可读性与数据保存的反馈。

### What to Test（简体中文）

欢迎体验云食册 2.0.0！本次重点是把家里的食材变成一顿饭。

1. 选择食材和厨具，或输入一句话找菜，看看推荐是否实用。
2. 打开做法和来源链接，收藏菜谱，重启后确认收藏还在。
3. 在冰箱添加食材、修改到期日、移出食材，体验临期提醒；拒绝通知也应可正常使用。
4. 创建、编辑和删除自己的菜谱，尝试导出和导入备份。
5. 添加、隐藏和恢复个人食材或厨具，检查选择器的变化。
6. 留意大字体、深色模式和 iPad 分屏下的文字与按钮。

已知范围：iCloud 生产同步和双设备行为仍待验收；个人食材/厨具配置及提醒设置只保存在当前设备。iOS 与 Web 暂通过文件手动迁移。iOS 私人菜谱暂不参与内置推荐、收藏或历史，备份不含图片和视频附件。请保留重要数据的备份。

遇到问题请从「我的 → 意见反馈」或 TestFlight 反馈，附上版本/构建号、设备/系统、操作步骤和发生时间。应用内反馈不会自动公开到需求墙。

### Review Notes（英文）

Yun Shi Ce is a native recipe discovery and personal cooking organizer for iPhone and iPad. No app account, demo credentials, payment or subscription is required. Select ingredients and cookware in the cooking tab to see matching recipes. Open a recipe to read instructions or follow its external video/source link. Favorites, history, fridge items and personal recipes are saved locally. Recipe text and search work offline; images, external videos and feedback require network access.

Optional iCloud features use the device's Apple account and the app's private CloudKit database. Personal ingredient/cookware settings and notification settings are local only. There is no automatic web account sync. Analytics is opt-in and disabled by default; declining it does not restrict any feature. Notifications are optional. The full privacy policy is available offline under My → Privacy. The app's feedback form submits private feedback to the support team.

The maintainer has not yet completed real production iCloud transfer and dual-device acceptance. These checks remain required before opening this candidate to general external testers.

审核联系人仅填写在 App Store Connect，不提交姓名、电话或审核联系邮箱到源码。应用不需要测试登录，设置 `demoAccountRequired=false`，不要提供个人 Apple 账户。Feedback Email 使用已有公开支持邮箱 `me@yunyoujun.cn`。

## 网站与链接

| 用途       | 地址与状态                                                                                   |
| ---------- | -------------------------------------------------------------------------------------------- |
| 隐私政策   | `https://cook.yunle.fun/privacy`，包含私人菜谱和个人词典说明；提交前核验官网页面             |
| 支持中心   | `https://support.yunle.fun/?product=cook`，已验证 HTTP 200                                   |
| 需求墙     | `https://support.yunle.fun/feedback?product=cook`，已验证 HTTP 200；公开建议，不接收私密数据 |
| 内测说明页 | `https://cook.yunle.fun/guide/ios-beta`，包含安装、测试范围、已知限制与反馈方式              |

官网与文档共用 `vitepress-theme-yunlefun`，无需另建报名数据库或营销站。旧文档 Pages 项目已于 2026-10-25 删除，旧域名由独立 Worker 永久重定向到官网，部署与验收记录见[文档站部署](./docs-site.md)。App Store Connect 与 TestFlight 的 `zh-Hans` 隐私政策地址已更新为官网并回读确认，TestFlight 营销地址也已更新为官网内测说明页。后续填写 Apple 材料前仍需核验官网 HTTPS、页面内容与平台保存值。旧地址 `https://cook.yunyoujun.cn/docs/privacy` 返回 404，不可填入审核资料。

## 开放前待办

Apple 的要求与本项目的数据验收分别核对，不能把本地包校验、CI 或生产 schema 匹配当作用户可用证明。

### 提交 Beta 审核

- [x] 上传经过本地和 Apple 校验的外测包，精确构建回读为 `VALID / APP_STORE_ELIGIBLE`，确认没有出口合规待处理项。
- [x] 填写 Beta 描述、Feedback Email、审核联系人、无需演示账号、Review Notes 和构建的 What to Test，并回读。
- [x] 创建首批外部组，关联精确构建，关闭自动通知，先不开放公开链接。
- [ ] 提交首个外测构建到 Beta App Review，保留审核回执；审核通过后才能开始外测。

2026-10-24 已补齐初始检查中缺失的材料；构建 5 的外测材料检查为 0 错误、0 警告，首批外部组已关联构建，测试者为 0，公开链接和自动通知关闭。该检查不覆盖全部外测条件，不能代替真机验收和 Beta 审核。后续状态以发布目录的最新回读为准。

### 向首批用户开放前

- [ ] 同一新候选构建的真机安装、原位升级、旧数据保留；新增/编辑/删除、强制退出后再启动。
- [ ] 真机私人菜谱和个人词典文件导出 → 导入，检查冲突、重复与取消；收藏/历史备份不混用。
- [ ] 同账号两台真机的收藏、历史、偏好、冰箱、私人菜谱生产读写及前台刷新；覆盖偏好晚到、离线重连与并发修改/删除，确认最终数据一致。
- [ ] 测试账号之间的数据隔离、未登录及退出 iCloud 后的本地数据保留；使用专用测试账号，不退出用户的主账号来做实验。
- [ ] 用新构建验证意见反馈提交、失败保留内容、后台私密可见；通知拒绝与统计拒绝不影响功能。
- [ ] 内测说明页部署可访问；公开的隐私说明与实际包一致。发布页面仍标记准备中，拿到真实邀请链接并决定开放后再替换。
- [ ] 先邀请约 20–30 位用户，覆盖实际支持的旧系统和较旧设备；确认无启动阻断、数据丢失或严重同步问题，再扩大人数。

目前仅有一台已验收构建 4 的真机，没有双设备结果。需要第二台可用于验收的真实设备；也可先邀请一位明确参与同步验收的协作者，在受控范围完成验证，再向普通用户开放。不要把未验证标成已通过。会员、Widget、付费墙和新的营销站不属于本次外测的前置条件。

官方依据：[提供测试信息](https://developer.apple.com/help/app-store-connect/test-a-beta-version/provide-test-information/)、[邀请外部测试员与 Beta 审核](https://developer.apple.com/help/app-store-connect/test-a-beta-version/invite-external-testers/)。
