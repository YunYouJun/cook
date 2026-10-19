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
