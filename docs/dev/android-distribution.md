# 云食册 Android 内测与上架

2026-11-25。Android 备案补录生效后，官网先开放正式签名 APK 下载，小米等应用商店随后上架。官网和国内商店使用同一个 `fun.yunle.cook` 包名、应用签名和版本序列。Google Play 可按受众另开测试轨道。

## 统一内测入口

| 对象                        | 安装方式                                                                                                                                            | 更新方式                                       |
| --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------- |
| 中国大陆首批体验用户        | 从官网的 Android 内测说明进入邀请下载。优先验证蒲公英内测模式的授权码或密码安装，核对当前账号可用的安装方式、下载额度与大陆访问，再从真机下载验包。 | 在同一入口下载新版 APK，保留旧安装并覆盖升级。 |
| 能使用 Google Play 的测试者 | 在 Play Console 上传 AAB，通过 Internal testing 邀请，单个 App 最多 100 人，需要 Google 账号及可用的 Play 商店。                                    | 通过 Play 商店接收测试轨道更新。               |

官网 [Android 下载页](/guide/android-download)集中说明版本、安装与升级及反馈方式。首批建议邀请 10–20 人，至少覆盖一台小米/Redmi 和一台未配置 Google Play 的真机。报名可以沿用 iOS 外测的方式，收集联系邮箱、机型、系统版本和是否可用 Play。Play 邀请时再收集对应 Google 账号邮箱。反馈归入云乐坊私密工单，记录分发渠道、应用版本、机型、系统和复现步骤。线上 App 内反馈验收之前，可使用[云乐坊支持中心](https://support.yunle.fun/?product=cook)或[开发者邮箱](mailto:me@yunyoujun.cn)。

蒲公英是 APK 下载与邀请入口，下载后由系统安装，用户可能需要为当前浏览器或文件管理器允许安装应用。对外发邀请前，向分发平台或接入服务商核对这轮测试适用的大陆 APP 备案与分发要求。对适用 Android 开发者验证的设备，长期提供 APK 下载时还需在相应的开发者控制台核对包名及签名登记。[蒲公英内测与分发规则](https://www.pgyer.com/doc/view/app_install) · [Google Play 测试轨道](https://support.google.com/googleplay/android-developer/answer/9845334) · [Android 开发者验证](https://developer.android.com/developer-verification/guides)

## 首包与升级

1. 原生版沿用 `fun.yunle.cook`。确认这个包名是否已有对外签名版本，以及各渠道的最高 `versionCode`。后续从同一版本序列取号，让测试者可以原位升级到正式版。旧 Capacitor 包 `cn.yunyoujun.cook` 通过文件迁移收藏和浏览历史，见[旧版迁移说明](./android-startup-2026-11-06#旧版食用手册的数据迁移)。
2. 首个邀请包使用可持续维护的应用签名密钥。若希望直装版与 Play 版能相互覆盖，首次配置 Play App Signing 时提供同一把应用签名密钥，再使用独立上传密钥签署 AAB。确认目标商店实际安装包的证书和版本号，并用实包验证原位升级。签名私钥和密码存放在私有凭据存储。[Android 应用签名](https://developer.android.com/studio/publish/app-signing)
3. 首批优先验证找菜、收藏、冰箱与备份，构建时关闭微信登录与注册、浏览器结账和 Play Billing。在 `apps/android` 按 [Android README](https://github.com/YunYouJun/cook/blob/main/apps/android/README.md#签名与分发产物) 准备签名凭据后执行下列命令。当前 `-Pcook.distribution=direct` 会启用浏览器结账，所以首批 APK 使用默认渠道配置。

   ```sh
   ./gradlew :app:assembleRelease -Pcook.play.billing=off -Pcook.signing.required=true --no-daemon --no-configuration-cache
   ./gradlew :app:bundleRelease -Pcook.distribution=play -Pcook.play.billing=off -Pcook.signing.required=true --no-daemon --no-configuration-cache
   ```

4. 对签名产物记录源码提交、包名、版本、文件 SHA-256 和安装证书指纹，复用 CI 的 16 KiB 对齐检查。按最终下载路径验收干净安装与原位升级，检查收藏、冰箱和私人菜谱在升级后仍可读取。使用无 Play 的真机验证离线找菜，用支持的旧系统与主流机型复查相机、通知与文件选择。
5. 以实际安装证书发布[两站的 App Links](./docs-site#android-app-links)，再验菜谱分享和浏览器登录自动回跳。[Android 隐私政策](/privacy-android)及[用户协议](/terms)已在官网复查为 HTTP 200，两个生产 `assetlinks.json` 仍为 HTTP 404。按最近一次[反馈联调记录](https://github.com/YunYouJun/cook/blob/main/apps/android/README.md)，线上函数仍需验收 Android 提交与私密回执。对外说明应与候选包的实际验收结果一致。

## 备案与商店提审

1. 按实际运营主体核查 APP 备案。iOS 和 Android 安装后均显示「云食册」。同名应用的不同运行平台可使用同一个备案号。若已有 iOS APP 备案，向接入服务商办理变更，补录 Android 的包名、签名公钥及证书 MD5。备案信息核对应用名称、主办主体、服务域名和包签名。取得适用的 APP 备案号后，在 App 显著位置标示并链接备案查询系统。[工信部 APP 备案通知](https://www.miit.gov.cn/zwgk/zcwj/wjfb/tz/art/2023/art_920db564162e4312916a01bed6540ad8.html) · [腾讯云 APP 备案常见问题](https://cloud.tencent.com/document/faq/243/97691)
2. 在[小米开发者后台](https://dev.mi.com/)确认海州区云乐坊信息技术工作室可通过开发者主体认证。小米现行注册标准暂不接受个人开发者新申请，公开标准未明确列出个体工商户的认证方式，以后台及开发者支持的答复为准。[小米开发者账号注册标准](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1731)
3. 提审材料按「云食册」准备，包括安卓版权凭证、APP 备案号、签名 APK、图标、介绍、截图、隐私政策与协议、第三方 SDK、权限用途、注销说明和支持入口。构建前设置实际服务备案号 `COOK_ANDROID_APP_FILING_NUMBER`，从「我的 → 关于云食册 → APP 备案」核对显示与查询跳转。当前客户端含联网智能找菜、步骤讲解和会员批量识图，提审前需按实际启用范围核对小米生成式 AI 的资质与审核要求。[小米资质 FAQ](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2251) · [APP 备案展示指南](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1832) · [小米特殊行业资质要求](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1139)
4. Google Play 先核对能管理 `fun.yunle.cook` 的账号。若使用 2023-11-13 之后注册的个人开发者账号，须完成至少 12 位测试者连续加入 14 天的 Closed testing，才可申请 Production 权限。若安排同一批用户转入封测，先退出 Internal testing 再接受封测邀请。付费测试可在 Play 商品及 Sandbox 验单服务就绪后单独安排。[Google Play 新个人账号测试要求](https://support.google.com/googleplay/android-developer/answer/14151465) · [Google Play 测试轨道](https://support.google.com/googleplay/android-developer/answer/9845334)

## 发布官网下载

1. 用正式密钥完成签名、16 KiB 校验和真机原位升级检查，再执行 `node scripts/qa/prepare-android-download.mjs --apk /path/to/app-release.apk`。命令核对包名、调试标记及 APK 签名，打印安装包 URL、`COOK_ANDROID_APK_RELEASE` 和证书指纹。
2. 将与验包完全相同的 APK 上传至打印的 CDN 路径。文件响应使用 `application/vnd.android.package-archive` 和 `Content-Disposition: attachment`。通过 HTTPS 重新下载，使用 `shasum -a 256` 对照生成的值，再用该下载包复验安装。小米提审使用同一份 APK，商店审核通过后从商店安装并核对证书与升级。
3. 在官网与网页版的 EdgeOne 构建环境登记 `COOK_ANDROID_LINKS_SHA256`，已有其他分发渠道时合并证书。在官网的构建环境设置命令输出的 `COOK_ANDROID_APK_RELEASE`，触发相应构建。官网没配置 APK 时显示准备状态，配置的证书未登记到 App Links 时官网构建会报错。
4. 发布后确认[下载页](https://cook.yunle.fun/guide/android-download)的版本与文件对应，并用 `node scripts/qa/verify-android-app-links.mjs --apk /path/to/app-release.apk` 核对两个域名。Android 12 及以上可用 `--verify-device` 检查系统域名验证与链接解析。下载与登录回跳都以真机结果为准。[两站的 App Links](./docs-site#android-app-links)

## OPPO 软件商店与国行一加

使用欢太账号登录 OPPO 开放平台并完成开发者认证。管理中心的「应用管理」或「移动应用列表」提供创建应用入口。应用名称和包名使用 `云食册`、`fun.yunle.cook`，提交与其他国内渠道共用的正式签名 APK。平台将读取版本和权限。应用核准须与包名和运营主体对应，软件版权材料按权利人与认证主体的关系选择自研或授权。[OPPO 移动应用上架指引](https://open.oppomobile.com/new/developmentDoc/info?id=10035) · [OPPO 资料填写规范](https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=6304)

以下文案适用于保留找菜、厨房工作台、冰箱和私人菜谱的首发包。交审前按实际包核对文案、分类、年龄分级和截图。

- 一句话简介为「按食材和厨具找菜谱」。
- 应用介绍为「云食册帮你用手头的食材和厨具找菜谱。选好食材就能查看匹配的菜谱、用料和做法，在厨房工作台备料、看步骤和计时。常做的菜可以收藏，冰箱可以记录食材和到期日，也可以保存自己的菜谱。普通找菜和已内置的文字做法支持离线使用。」
- 版本说明为「云食册原生 Android 首次发布，支持按食材找菜、收藏、厨房计时、冰箱记录和私人菜谱。」
- 审核补充说明为「核心功能免登录。可离线选择番茄和鸡蛋，查看匹配菜谱，收藏后进入厨房工作台。冰箱支持手动记录食材和日期；主动拍摄时才请求相机权限。通知用于厨房计时和冰箱临期提醒。」
- 隐私政策使用 [Android 隐私政策](https://cook.yunle.fun/privacy-android)。适用条款、支持方式及权限用途以正式包为准。保留联网生成式 AI 时，核对安全评估报告及相应凭证。[OPPO 应用审核 FAQ](https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=6307)

备案和商店使用 [512 px 俯视锅 PNG](/brand/cook-app-icon.png)，与 Android 桌面图标共用同一图形。竖版截图使用实际 Android 候选包，准备 3–5 张 1080 × 1920 的 JPG 或 PNG，每张小于 1 MB。可覆盖找菜、图文菜谱、厨房工作台和冰箱。[OPPO 素材与上传要求](https://open.oppomobile.com/bbs/forum.php?mod=viewthread&tid=6304)
