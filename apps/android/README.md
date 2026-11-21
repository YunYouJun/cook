# 云食册 Android

原生 Android 预览版。 `:app` 使用 Kotlin、Jetpack Compose、Navigation 3 和 Room，`:core` 是纯 Kotlin/JVM 模块，和 Web、iOS 共用菜谱数据与测试样例。

## 构建

使用 JDK 21、Android SDK Platform 37.2 和 Build Tools 37.0.0。Gradle、AGP 和 Kotlin 版本由仓库里的 Wrapper 和 Version Catalog 固定。

在仓库根目录运行：

```sh
pnpm install --frozen-lockfile
pnpm convert
cd apps/android
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`，包名为 `fun.yunle.cook.debug`。Release APK 启用 R8 和资源压缩，输出为未签名包。

## 与 iOS 的功能对齐

| 功能                 | Android 实现                                                                                                                                                                                 |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 找菜、云厨房和菜谱库 | 找菜可切换 3D 云厨房。点场景食材或食材标签使用同一套筛选条件，点锅可查看匹配。菜谱库能搜索全部菜谱、收藏和最近浏览，随机卡片支持翻看、换一道和摇一摇。                                       |
| 菜谱分享             | 使用系统分享面板和 Web、iOS 共用的 `https://cook.yunle.fun/recipes/share/?id=…` 链接。Android 已声明 App Links 路由，系统自动跳转需在该域名配置正式签名的 `assetlinks.json`。                |
| 厨房工作台和做菜足迹 | 公开菜谱可备料、计时和记录完成。公开菜谱和私房菜均可记录做过、学会及撤销。本机「我的拿手菜」支持配图及离线 3D 旋转和缩放。                                                                   |
| 冰箱                 | 支持改日期、标记用完和恢复原批次。拍照或选图后优先在本机识别包装文字，匹配不到食材时尝试离线识别 22 类蔬果。会员可确认接收方后上传去除元数据的照片进行批量识图。两种方式均在用户确认后入库。 |
| 账号、AI 和文件      | 浏览器登录、一次性会话交接和网站注销，显式启用会员收藏及历史同步。支持测试个人 DeepSeek Key、联网找菜发送确认、编辑私房菜及三种 JSON 备份。                                                  |

管理云乐坊账号时，Android 通过浏览器发起 45 秒有效的一次性会话交接。Android 15 及以上在站点关联成功后可自动返回 App。旧版 Android 或网页停留在授权回调页时，可以复制链接并回到 App 粘贴。会话交接与 SSO 回跳使用 `cook.yunyoujun.cn`，菜谱链接使用 `cook.yunle.fun`。[站点关联的发布配置](../../docs/dev/docs-site.md#android-app-links)适用于两个域名。

3D 场景采用 Filament 渲染，应用内置云厨房、通用餐盘和三道特色菜模型。更新 iOS 的 USDZ 后，可在仓库根目录用 Blender 4.4 运行 `blender -b --factory-startup --python-exit-code 1 --python scripts/export-android-scenes.py`，生成对应的 GLB。

首次进入「拍照识别」时，App 提示下载约 25 MiB 的 TinyCLIP 蔬果模型。用户确认后，App 根据 [蔬果模型清单](../../assets/android/produce/model.json) 从 EdgeOne 下载，校验文件大小和 SHA-256 后保存在 App 私有目录，之后可离线识别蔬果。包装文字识别和手动录入随时可用。照片和包装文字均在手机内存中处理。可用 `uv run --script scripts/export-android-produce.py` 重新导出模型。[模型来源与许可证](https://huggingface.co/wkcn/TinyCLIP-ViT-39M-16-Text-19M-YFCC15M)

Gradle 根据 [3D 模型清单](../../assets/android/models.json) 从 `https://assets.yunle.fun/cook/android/` 下载 GLB，校验文件大小及 SHA-256 后打进 APK。下载后的文件缓存到 `app/build/downloaded/cookModels`，保留缓存即可用 `--offline` 构建。APK 仅包含蔬果模型的清单、标签和许可。

更新模型时，上传新导出文件到现有 COS 桶的 `cook/android/` 前缀，文件名使用 `<模型名>-<sha256>.<扩展名>`。从 EdgeOne 下载验证后，更新清单里的文件名、字节数和哈希。Git 保留导出脚本、许可、标签和清单。

拿手菜素材包的单独下载和更新以及 Google Play 会员结算仍待实现。CloudKit 与 StoreKit 属于 Apple 平台，Android 使用现有云乐坊会员和跨端同步接口。

[三端共享契约](../../docs/dev/cross-platform-core-ci.md)说明了 Swift、TypeScript 和 Kotlin 的测试对照。正式签名、历史包迁移、App Links 与生产账号、批量识图、反馈及同步联调按[路线图](../../docs/dev/android-roadmap.md)单独验收。

## 直装版会员购买与权益核对

从「我的 → 云乐坊会员」可以核对当前账号的有效期。直装版可通过一次性账号交接打开云乐坊网页，在账号详情选择开通或续费。月付、年付方案和支付确认由现有网页提供。返回 App 会重新读取当前账号权益，换设备后也可用「核对已购会员」恢复显示有效期。[云乐坊会员页面](https://www.yunle.fun/pricing)

从 `apps/android` 为直装渠道构建时使用 `./gradlew :app:assembleRelease -Pcook.distribution=direct`。默认构建和 Play 商店安装的 App 提供权益核对。Play 的购买需要对应渠道的结算实现。[Google Play 支付政策](https://support.google.com/googleplay/android-developer/answer/9858738)

## 原生微信联调

Android 复用 iOS 的云乐坊微信绑定、登录和注册接口。已注册的手机号走原账号登录与绑定。新账号先验证短信，在用户确认并完成微信授权后才注册。[共用微信账号契约](../../docs/dev/ios-wechat-login.md)

登录和注册入口默认关闭。在已具备开放平台配置和可用服务端的联调环境，从 `apps/android` 运行下列命令。

```sh
./gradlew :app:assembleRelease -Pcook.wechat.login=true -Pcook.wechat.signup=true
```

仅联调绑定和登录时，可以省略注册开关。默认 AppID 为 `wxce6c7405dd756b9d`，如联调环境使用独立应用，可通过 `-Pcook.wechat.app-id` 设置与该后端对应的公开 AppID。微信开放平台需登记实际安装包的 Android 包名与签名。Release 包名是 `fun.yunle.cook`，Debug 包名是 `fun.yunle.cook.debug`。AppSecret 由云乐坊服务端管理。

用登记过的签名安装到有微信的 Android 真机后，验收已有账号绑定前后的同一云乐坊 UID、取消授权、未绑定微信登录、注册验证码和开户后的会话恢复。服务端开关与开放平台登记需分别确认。
