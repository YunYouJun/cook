# 云食册 iOS App

SwiftUI 原生 App，计划和验收标准见 [iOS App 打磨目标](../../docs/dev/ios-roadmap.md)。

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

## iCloud 同步的结构（CloudKit schema）

收藏、浏览记录和做菜偏好用 SwiftData 存，登录 iCloud 时通过容器 `iCloud.fun.yunle.cook` 同步。TestFlight 和 App Store 版本用 CloudKit 的 **Production** 环境，结构要先在 Development 环境建好，再手动部署过去：

1. 用 Xcode 在**真机**上跑 Debug 版（已登录 iCloud）。第一次启动会调用 `initializeCloudKitSchema()` 把 `CD_Favorite`、`CD_HistoryEntry`、`CD_Preferences` 建到 Development，首屏会多等几秒；日志里看到「已在 Development 环境建好 CloudKit 结构」就是成功了。模拟器和 Release 构建不会跑这一步（见 `Cook/Model/CloudKitSchema.swift`）。
2. 打开 [CloudKit Console](https://icloud.developer.apple.com/dashboard/) → `iCloud.fun.yunle.cook` → Development → Record Types，确认这几个类型和字段都在。
3. 点左下角 **Deploy Schema Changes…**，检查变更只有新增（类型、索引和对应的默认权限），再点 Deploy。Production 的字段之后只能加不能删改，部署前想清楚。
4. 在 Production → Record Types 确认已经同步过去，再用 TestFlight 版本验证两台设备之间的收藏同步。

改了 `@Model` 的字段时：把 `CloudKitSchema.version` 加一，真机 Debug 启动一次，然后重复第 2–4 步。如果在 Console 里 Reset 了 Development 环境，用启动参数 `-cloudkit.schemaInitialized 0` 让开发版重新建一次。
