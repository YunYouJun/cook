# iOS 会员购买与恢复

## 已确认的产品方向

2026-11-06 维护者选择同时支持单次购买与自动续费，各提供月／年选项。Web 与 iOS 共用云乐坊会员；已付款的新周期顺延，退款只回退对应 Apple 购买，遇到后续独立购买时保留权益并转人工核对。自动续费仍按 Apple 账期扣款，已有 Web／单次时长不会暂停 Apple 扣款；会员页必须明确区分两种购买方式和续费管理。

维护者进一步提出默认共用云乐坊会员并询问买断。当前实现继续共用同一会员；未确认新增永久权益、买断范围或价格，不创建买断 SKU。基础做菜保持免费，持续消耗的智能服务不能由客户端推定为永久无限量。

两类周期需要分别记录付款凭据和 Apple 续费状态，不能把“取消自动续费”当作退款，也不能把某条 Apple 权益撤销当作清空账号会员。自动续费的续订、宽限、取消续订及退款仍须单独实现并验收；原平台的非续期周期计算不能直接代表这些行为已支持。

## 只读盘点 · 2026-11-06

通过现有 `asc` 登录执行：

```sh
asc apps list --bundle-id fun.yunle.cook
asc iap list --app 6824589542
asc subscriptions groups list --app 6824589542
asc subscriptions list --app 6824589542
```

应用 `6824589542`／`fun.yunle.cook`／「云食册 - 食用手册」存在；IAP、订阅组及订阅查询均为空。应用的正式和 Sandbox 通知 URL 也为空。没有创建商品、设置价格或配置通知。

拟使用独立商品标识，最终以上架配置回读为准：

| 购买方式 | 月                                  | 年                                 |
| -------- | ----------------------------------- | ---------------------------------- |
| 单次     | `fun.yunle.cook.member_month`       | `fun.yunle.cook.member_year`       |
| 自动续费 | `fun.yunle.cook.subscription_month` | `fun.yunle.cook.subscription_year` |

自动续费月／年属于同一订阅组和同一会员级别；用户只选一种扣款方式。定价尚未确定；原生页只使用 StoreKit 的本地化商品名称和价格。`StoreKitValidation` 中 1.00 的商品及 `.local.*` 标识仅是测试夹具，不是商业定价或 ASC 商品。

平台权威源为 `YunLeFun/www.yunle.fun` 的 `cloudfunctions/wxpay-order/lib/`，盘点时本地与远端 main 均为 `0d9622c549464842a59186832cbdc33b5e727da7`。修改后执行该仓库的 `pnpm sync:wxpay-lib`；不能修改 `YunLeFun/api` 中的旧 IAP 镜像代替权威源。现有 `fun.yunle.apps.*` 属于另一款应用，不能配置进 Cook。

该仓库的 `docs/ios-iap-settlement.md` 记录今天四个正式函数上线并下载核对，但独立 Sandbox 函数和测试账本未部署。首次 CLI 未登录；维护者恢复既有登录后，已核对 `iap-order` 为部署完成、bundle 为 `fun.yunle.apps`，并重新下载 `iap-order`／`appstore-notify`：45 个源码及依赖清单文件与上述 main 逐字节一致，两包内 Node SDK 均为 3.18.7。此处只独立回读这两个入口，未把历史四函数记录当作本次全部实测；也没有通过源码和上线记录推定 Cook 已联调。后续需建立 Cook 独立配置入口，不能覆盖另一款应用的 `APPSTORE_BUNDLE_ID`。

## 基础实现

`CookCore` 的 `MembershipPurchaseCoordinator` 只负责账号和交易状态：商品、取消、pending、监听、失败补交、恢复，以及服务端确认后 finish。StoreKit 持久的 unfinished 队列负责重启恢复；本机不自行发会员。账号使用 session UUID 防止退出／切号／同账号重新登录时旧响应写入新会话；Cook 没有历史 ASC 商品，缺失账号标记的交易不允许自动认领。

`MembershipAccountToken` 与权威 Node 实现逐项核对三个 UTF-8 向量。算法中的 `fun.yunle.apps.iap-owner.v1` 是既有平台算法版本，不是 Cook 的 bundle 或商品；服务端仍须独立核对已认证 UID、Apple 签名、bundle、商品及环境。

`NativeMembershipStoreKit` 接受明确的商品／类型配置，不使用其他 App 的 SKU。正常启动不调用 `AppStore.sync()`；只有用户恢复操作同步 App Store，随后遍历完整已验证历史。非续期商品的 `currentEntitlements` 只提供每种商品的最新记录，恢复多次单次购买仍依赖自有持久账本及幂等结算。参见 [Apple 当前权益序列](https://developer.apple.com/documentation/storekit/transaction/currententitlements)、[同步操作](<https://developer.apple.com/documentation/storekit/appstore/sync()>)、[未完成交易](https://developer.apple.com/documentation/storekit/transaction/unfinished)。

生产购买及会员入口默认关闭；原生会员页与专属服务适配在后续分支实现，不代表支付服务已经部署。服务端必须明确确认对应交易和环境后，编排才允许 finish；Xcode、Sandbox、Production 之间不回退。退款由服务端重算／安全回退并重新读取有效会员，本机不因某条 Apple 撤销直接删除 Web 权益。自动续费通知需按 [Apple Notifications V2](https://developer.apple.com/documentation/appstoreservernotifications/receiving-app-store-server-notifications) 和 [Server API](https://developer.apple.com/documentation/appstoreserverapi/get-transaction-history) 独立核对。

## 验证与交付边界

- 基础分支 14 项 CookCore 专项覆盖取消、待批准、未确认付款、付款前错误、重启补交、重复投递合并、切号与 ABA、缺失账号标记、环境／响应不匹配、到期及独立 Web 权益保留。它们使用协议夹具，不证明远端数据库事务。
- 真实本地 StoreKit 使用独立 `CookMembershipStoreKit` Scheme，测试配置只在测试 bundle 中。模拟器构建使用 `CODE_SIGNING_ALLOWED=YES CODE_SIGN_IDENTITY=-`，无需分发证书；禁用并行测试。基础层测试涵盖两类商品目录和非续期交易、账号标记、unfinished、finish 后历史、取消、pending→updates、退款及结算失败恢复。当前结果以本轮结果包为准，不以测试数量推定通过。
- 未签名的 iOS 26.5 首轮配置操作报 `SKInternalErrorDomain Code=3`；未签名的 iPadOS 27 后续出现 Apple 登录提示，已取消并中止，没有输入 Apple 凭据或真实扣款。沿用平台既有 ad-hoc 签名参数后，iPadOS 27 单项购买／恢复 0.648 秒通过。历史失败保留，不能计入通过数。[Apple DTS 对相关配置问题的记录](https://developer.apple.com/forums/thread/826971)仅用于解释诊断方向。
- iOS 26.5 的后续签名组合运行仍因本地配置不可用而失败，236 项 App／账号测试通过、七项 StoreKit 测试未通过，保留系统限制。现有 iPhone Duo／iOS 27.1 独立 Scheme 首轮为五项通过、两项失败：自动续费首次冷运行超过新设的 60 秒预算，后续定向 0.629 秒通过，尚不能据此认定根因消失；故障注入将 `generic(userCancelled)` 实际转换为 `StoreKitError.unknown`。注入测试现在明确核对该未知失败不会报告付款／恢复成功，而不将其算作用户实际取消的证据；实际取消仍须会员 UI 验收。StoreKit 单例限时保留为 120 秒，任务限时 10 分钟，不新增重试。
- 最终复测：iPhone Duo／iOS 27.1 和 iPad Pro 11 英寸 M5／iPadOS 27 各通过 **234 项 App 单元、2 项账号 UI、7 项本地 StoreKit**，没有跳过。共享一次常规构建 34.86 秒；手机常规会员组 116.54 秒、独立 StoreKit（含签名构建）47.69 秒；平板分别为 117.00／42.47 秒。每端合计含冷构建约 3 分 20 秒，只是本机实测，不能当作远端耗时；对应报告目录为 `financial-reviewed-contracts/`。原 26.5 失败及首次冷启动超时记录继续保留，不由后续通过覆盖。
- 原生 CI 的会员专属输入选择 `membership`：两端各运行全部 App 单元测试、本地 StoreKit 和账号 UI。混入工程、共享协议、数据或其他未知输入继续核心矩阵。核心和手动完整回归也保留本地 StoreKit；金融断言失败不重试，不能由另一台设备通过掩盖。
- 真实 Apple Sandbox、云端事务／并发、换机重装、完整会员 UI、VoiceOver、大字号、TestFlight 分发与正式上架均须分别留证。本地 StoreKit 和模拟器不能替代真实沙盒。

本机本轮报告位于 `~/Library/Developer/ReleaseArtifacts/Cook/20261106-membership-foundation/`；任务独占 DerivedData／结果包在导出日志与摘要后回收，共享设备仅恢复本轮启动前的关闭状态，不新增或清空设备。

### Linux 工具链验收

基础 PR 首次远端运行 `42242072527` 的 Swift job 在测试执行前链接失败：`libswiftObservation.so` 缺少 `swift::threading::fatal`，并非会员断言失败。CI 固定版本由 6.2.1 升至 6.2.2，保留全部 Linux 测试及 Observation；[Swift 6.2.2 官方发布说明](https://forums.swift.org/t/announcing-swift-6-2-2/83637)列出对应修复。后续运行结果单独记录，首次失败不计为通过。

第二次 `42246896481` 在安装阶段被 setup-swift v2.4.0 的内置版本清单拒绝（最高 6.2.1），未执行测试。安装 action 改为固定 v3 源码提交 `364295d9`，使用 Swiftly 安装精确的 6.2.2，保留默认签名验证；不通过降级测试或禁用 Observation 绕过链接错误。

`42248202376` 的安装器在校验 Swiftly 下载签名时缺少公钥，尚未执行 Swift 测试。按 [Swiftly 官方 Linux 安装文档](https://www.swift.org/install/linux/swiftly/)先导入 swift.org 发布公钥，再由固定 action 验证下载签名；没有启用 `skip-verify-signature`。

### 首次自动续费 SDK 调用与场景验收

`42249053236` 的 iPad 本地 StoreKit 首项在自动续费 `Product.purchase` 调用处超过 120 秒，其余六项通过；该运行不能算交易门禁通过。此前本机冷启动的同类 60 秒超时亦继续保留。Apple [发行说明](https://developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-27_3-release-notes)记录首次使用 SKTestSession 时调用可能挂起；这是相似现象的官方背景，不能据此断定当前 27.1 的根因已经证明。

自动续费购买、账号标记、环境、finish、取消续订保留已购记录及恢复的原断言移至 #203 会员页的真实本地 Apple 确认流程，使用生产页面同样的 SwiftUI PurchaseAction 场景；不再用无交互场景的首笔自动续费调用代替用户购买路径。基础层只证明自动续费目录类型，非续期完整交易及其他金融断言继续执行。没有增加重试、延长 120 秒限时或忽略失败，不能把目录测试当作自动续费交易通过。

## 原生会员页面与购买前校验（后续分支）

`MembershipService` 通过固定官方 Functions API 和当前登录的 Bearer token 读取账户会员，不使用 Web SDK 包装、不传客户端 UID、不跟随跳转。购买仅访问 Cook 独立入口 `cook-iap-order`／`cook-iap-order-sandbox`，严格核对应用、bundle、商品类型和账本环境。购买前重新查询 `getCapabilities`；不支持、未部署或账号变化时不打开 Apple 付款。服务端响应须确认同一订单，未确认交易继续保留；权益和补交都离线时仍显示购买待核对。

会员页区分单次与自动续费，使用 StoreKit 本地化名称和价格，提供恢复购买、继续核对及订阅管理；明确 Web 时长不暂停 Apple 续费。`COOK_MEMBERSHIP_UI_ENABLED` 与 `COOK_MEMBERSHIP_PURCHASES_ENABLED` 默认均为 NO。UI fixture 仅在 DEBUG 的显式测试参数下启用，以独立本机账号和 defaults 存储，不产生认证凭据、网络或正式权益。真实本地 Apple 取消测试仅使用 `.local.*` SKU。

客户端协议与页面的通过结果在 PR 中分别列示；本地假服务、真实本地 StoreKit、远端 CI 和真实 Sandbox 不互相替代。买断及自动续费服务端生命周期仍为后续产品／实现边界。
