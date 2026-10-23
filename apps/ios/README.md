# 云食册 iOS App

SwiftUI 原生 App，计划和验收标准见 [iOS App 打磨目标](../../docs/dev/ios-roadmap.md)。

TestFlight 发布参数、正式反馈配置和内部组验收见 [原生发布流程](../../docs/dev/ios-testflight.md)。

- `CookCore/`：推荐、搜索、相克判断，移植自 `packages/cook`，和 Web 跑同一份用例。
- `Cook/`：App 界面（SwiftUI）、收藏和浏览记录（SwiftData，有 iCloud 权限时通过 CloudKit 同步）。
- `project.yml`：XcodeGen 工程定义，生成的 `Cook.xcodeproj`、`Info.plist`、`Cook.entitlements` 不提交。

## 本地运行

需要 Xcode 26 和 [XcodeGen](https://github.com/yonaskolb/XcodeGen)（`brew install xcodegen`）。

```bash
# 在仓库根目录
pnpm install
pnpm convert                                  # 生成菜谱 JSON，构建时会拷进 App
xcodegen generate --spec apps/ios/project.yml
open apps/ios/Cook.xcodeproj
```

真机调试和上传 TestFlight 需要先 `export APPLE_DEVELOPMENT_TEAM=你的 Team ID` 再运行 `xcodegen`，并在开发者后台为 `fun.yunle.cook` 开启 iCloud（容器 `iCloud.fun.yunle.cook`）。

```bash
swift test --package-path apps/ios/CookCore   # 只跑核心逻辑
```

CI 的 `ios` job 在 iPhone 和 iPad 模拟器上先后跑单元测试和 UI 测试。失败时 `Show test failures` 一步会写出失败原因，完整结果包（截图、每一步的操作记录）在这次运行的 Artifacts 里，名为 `ios-xcresult`，下载后用 Xcode 打开。

## 反馈入口

「我的 → 反馈」中的「需求墙」直接打开 `https://support.yunle.fun/feedback?product=cook`，保留 Cook 筛选；「支持中心」打开支持中心首页。意见反馈仍在 App 内私密提交，失败时保留内容并提供支持中心私密反馈入口，菜谱纠错保持原流程。

原生入口的发布与实际分发构建验收由 [#133](https://github.com/YunYouJun/cook/issues/133) 跟踪，源码合并不代表已经分发。

## iCloud 同步的结构（CloudKit schema）

收藏、浏览记录、做菜偏好和冰箱食材用 SwiftData 存，登录 iCloud 时通过容器 `iCloud.fun.yunle.cook` 同步。TestFlight 和 App Store 版本用 CloudKit 的 **Production** 环境，结构要先在 Development 环境建好，再手动部署过去：

1. 用 Xcode 在**真机**上跑 Debug 版（已登录 iCloud）。第一次启动会调用 `initializeCloudKitSchema()` 把 `CD_Favorite`、`CD_HistoryEntry`、`CD_Preferences`、`CD_FridgeItem` 建到 Development，首屏会多等几秒；日志里看到「已在 Development 环境建好 CloudKit 结构」就是成功了。结构版本 2 包含冰箱食材，之前完成过版本 1 的设备也会重新初始化。模拟器和 Release 构建不会跑这一步（见 `Cook/Model/CloudKitSchema.swift`）。
2. 打开 [CloudKit Console](https://icloud.developer.apple.com/dashboard/) → `iCloud.fun.yunle.cook` → Development → Record Types，确认这几个类型和字段都在。
3. 点左下角 **Deploy Schema Changes…**，检查变更只有新增（类型、索引和对应的默认权限），再点 Deploy。Production 的字段之后只能加不能删改，部署前想清楚。
4. 在 Production → Record Types 确认已经同步过去，再用 TestFlight 版本验证两台设备之间的收藏同步。

改了 `@Model` 的字段时：把 `CloudKitSchema.version` 加一，真机 Debug 启动一次，然后重复第 2–4 步。如果在 Console 里 Reset 了 Development 环境，用启动参数 `-cloudkit.schemaInitialized 0` 让开发版重新建一次。

## 上架前 P0 验收

隐私政策正文维护在 [`docs/privacy.md`](../../docs/privacy.md)，由 XcodeGen 直接作为资源打包；修改正文后要重新构建 App。设置、统计同意页和意见反馈页都能离线打开政策。统计使用独立的同意记录，默认关闭，旧版默认开启不算用户同意；无反馈服务配置时不展示统计开关。

`CookTests` 覆盖同意迁移、撤回后的请求取消、收藏保存失败回滚与幂等重试、导入失败的原子回滚。`CookUITests` 使用独立同意记录和内存数据库，不读写用户数据、不发送反馈请求；覆盖正常收藏、连续失败重试、隐私政策入口和同意状态重启保留，以及拒绝统计后添加和移出冰箱食材。

2026-10-23 的生产检查记录与尚待完成的验收见 [iOS P0 验证记录](../../docs/dev/ios-p0-validation.md)。
