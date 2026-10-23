# iOS P0 验证记录

检查日期：2026-10-23（北京时间）。本次修改尚未上传 TestFlight，也未发布网站。

## 本次修复

- 「我的 → 隐私」新增完整离线[隐私政策](/privacy)，统计选择和意见反馈也可进入。App 与文档站共用 `docs/privacy.md`。
- 推荐统计默认关闭。新安装及没有新同意记录的升级用户需主动选择，旧版默认开关不视为同意。拒绝不影响功能，可在设置中开启或撤回；撤回时取消排队及进行中的统计请求，不补发此前操作。
- 详情、列表滑动和长按收藏共用保存处理。失败恢复原状态并显示取消、重试；重试保存目标状态，避免再次切换。只在保存成功后给成功反馈。
- 导入收藏和历史时，保存失败统一回滚，显示「导入未能保存」，不再报告虚假的导入完成。
- 隐私清单补充邮箱，并将可与联系方式一起保存的反馈内容标记为可能关联身份。
- 与远端 `481dd5e` 整合，保留冰箱食材、临期通知、通知跳转、CloudKit 结构版本 2 及 CI 顺序执行两个模拟器的修复；政策补齐冰箱字段、同步、删除与通知说明。
- 随后同步 `main` 的 `82b4179`，保留支持中心入口与私密反馈流程；解决设置页冲突，并同步政策中的应用意见接收方和旧反馈记录说明。

## 本地验证

- Debug 与 Release 模拟器构建通过；Release 中的政策与源文件一致，二进制不含 UI 测试开关和 Development Schema 初始化代码。未进行分发签名或上传。
- 整合后重新验证，Xcode 27.1、iOS 27 模拟器：iPhone 18 Pro 与 iPad Pro 11-inch (M5) 各 34/34 项通过（30 项单元测试、4 项界面测试），无失败或跳过。
- 界面测试覆盖正常收藏、连续两次保存失败后重试、隐私入口与同意状态重启保留、拒绝统计后添加和移出冰箱食材。冰箱的日期、跨时区与夏令时通知计划由单元测试覆盖；收藏保存回滚也验证不会丢失已有冰箱数据。
- 首轮 CI 中 iPhone 17 Pro（iOS 26.5）34/34 通过；13 英寸 iPad 的隐私用例暴露测试误点：整行宽 992 点，90% 落点在实际系统开关左侧。相同尺寸本地复现后，测试改为定位行内的真实开关元素；iPhone、11 英寸和 13 英寸 iPad 的完整隐私用例分别复测通过，保留开启、重启和关闭的状态断言。
- Web Vitest 初轮 32 个文件、290 项测试通过；整合支持中心后重新运行，33 个文件、296 项全部通过。CookCore 13 项测试、TypeScript 类型检查与数据校验通过（数据校验 0 错误，6 条既有重复菜谱提示）。
- 单元测试宿主和 UI 测试均使用内存数据库；UI 测试的同意记录使用独立 UserDefaults，网络传输被测试替身阻断。
- 隐私清单通过 `plutil -lint`，源码通过 ESLint，补丁通过 `git diff --check`。全目录 lint 会扫描工作区原有未跟踪的 `app/data/*.json` 并报告格式错误；源码检查排除原有 `app/` 与 `artifacts/`，这些文件不随本次提交。
- 文档站可正常构建并生成 `privacy.html`；App 的资源测试确认同一份政策正文已打包。

测试通过不代表 CloudKit 生产同步通过：上述测试使用内存库，没有生产 iCloud 账户。

## CloudKit Production

此前检查容器为 `iCloud.fun.yunle.cook`，环境为 **Production**，检查范围是合并冰箱功能前的三个模型。本次只读检查，没有部署或修改生产结构及数据。

| 检查项                      | 观察结果                                                                              | 结论                                |
| --------------------------- | ------------------------------------------------------------------------------------- | ----------------------------------- |
| Schema History              | 2026-10-20 创建 `CD_Favorite`、`CD_HistoryEntry`、`CD_Preferences` 及对应索引         | 生产结构已部署                      |
| Favorite 字段               | `recipeId` 为 Int64；`createdAt`、`updatedAt`、`deletedAt` 为 DateTime                | 与当前模型一致                      |
| HistoryEntry 字段           | `recipeId` 为 Int64；`visitedAt`、`updatedAt`、`deletedAt` 为 DateTime                | 与当前模型一致                      |
| Preferences 字段            | `pantry`、`avoid` 为 Bytes；`mode` 为 String；`noSpicy` 为 Int64；时间字段为 DateTime | 与当前模型一致                      |
| Telemetry，10 月 19–22 日   | 113 次请求；Errors 与 Error Rate 为「—」                                              | 不能把「—」当作已验证零错误         |
| Logs，最近 1 周，无过滤条件 | 查询返回 0 events                                                                     | 没有可用于定位同步成功或失败的事件  |
| Usage，月范围               | Requests by Device Type 为 Other 100%；OS Version 无结果                              | 未取得 iOS 客户端生产同步成功的证据 |
| 本机可访问的真机            | 一台 iPhone 上安装 2.0.0 (1)，`builtByDeveloper = true`                               | 开发构建不用于 TestFlight 生产验收  |

结论：当时三个模型的生产结构检查通过。同步远端后，当前代码还包含 `FridgeItem`（`CloudKitSchema.version = 2`）；**`CD_FridgeItem` 的生产结构及双设备生产同步尚未验收**。整合后尝试复查时 Apple 登录会话已失效，未重新取得冰箱模型的生产状态。控制台请求量和模拟器测试不能替代真实的 CloudKit 同步结果。

## 上架前仍需完成

### 两台真机的 TestFlight 同步

先确认 Production 已部署 `CD_FridgeItem`（`name`、`addedAt`、`expiresAt`、`createdAt`、`updatedAt`、`deletedAt` 及 SwiftData 所需字段和索引）。两台设备使用同一 Apple 账户，开启本 App 的 iCloud，安装同一个待发布 TestFlight 构建，记录版本、构建号和每步时间：

1. 在 A 收藏一道未收藏的菜，确认 B 出现且重启后仍在。
2. 在 B 取消该收藏，确认 A 消失；再次收藏，确认两端恢复且没有重复项。
3. 在 A 浏览另一道菜、修改常备食材和忌口，确认 B 的历史与偏好同步。
4. A 断网后变更收藏，确认本地成功保存；恢复网络，确认 B 最终一致。离线保存本身不应显示保存失败。
5. 在 A 添加冰箱食材、修改到期日，确认 B 的食材名称和日期一致；在 B 移出，确认 A 同步消失。两端分别授权临期提醒，确认通知按各自设备设置排程，点开可进入冰箱首页。
6. 对照 Production 的同步记录或设备 CloudKit 日志记录结果；如果超时，保留错误码和发生时间，不反复部署已经存在的 Schema。

验收前检查导出的分发包签名权限：容器为 `iCloud.fun.yunle.cook`，`com.apple.developer.icloud-container-environment` 为 `Production`，推送权限为 `production`。不能仅凭源码中的开发 entitlement 推断最终分发包的环境。

### 公开政策及 App Store 元数据

文档站隐私政策已加入源码并可构建。检查时 `https://cook.yunyoujun.cn/docs/privacy` 返回 HTTP 404，因此 App 只提供完整离线政策，没有链接到这个尚未发布的地址。发布文档站后，应验证公开 URL 返回政策正文，再填入 App Store Connect 的隐私政策 URL；App 隐私申报需与更新后的 `PrivacyInfo.xcprivacy` 一致。
