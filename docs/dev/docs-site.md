# 官网、文档与网页版部署

## 域名分工

| 地址                  | 用途                                          | 部署产物                   |
| --------------------- | --------------------------------------------- | -------------------------- |
| `cook.yunle.fun`      | 云食册产品官网、iOS 发布信息和使用 / 开发文档 | `dist-site`                |
| `cook.yunyoujun.cn`   | 食用手册网页版                                | `dist`                     |
| `docs.cook.yunle.fun` | 旧文档入口，按原路径永久重定向到官网          | 独立重定向服务，无站点构建 |

文档和产品首页共用 `docs` 中的一份 VitePress 源码、导航和搜索，标准地址（canonical）与 sitemap 使用 `https://cook.yunle.fun`。App、公开页面、README 和平台资料中的文档链接统一使用官网。旧文档域名只承担历史链接兼容，不再部署第二份内容。微信开放平台已经提交的 `https://cook.yunyoujun.cn/about/` 及其用户协议保留可用，审核期间不因本次域名分工而删除。

### 开发预览 cook.yunle.app

`cook.yunle.app` 是遗留的 Vercel 开发预览。官网 `https://cook.yunle.fun` 和网页版 `https://cook.yunyoujun.cn` 均由 EdgeOne 发布，各自使用自己的 canonical。

仓库根目录的 `vercel.json` 给新 Vercel 部署的所有路径加上 `X-Robots-Tag: noindex, nofollow`。EdgeOne 使用自己的配置，两个正式域名不受影响。预览的 `robots.txt` 继续允许抓取，方便搜索引擎读取 `noindex`。

`cook.yunle.app` 绑定在 [Vercel 的个人 Cook 项目](https://vercel.com/yunyoujuns-projects/cook)，其生产分支是 `dev`；合并到 `main` 不会更新它。已有部署通过该项目 **CDN → Routing Rules** 的响应头规则设置相同的值，可用 `curl -I https://cook.yunle.app/` 核对。若要退役 Vercel，先为旧域名配置并验证指向正式站点的永久跳转，再解除 Git 连接；解除连接本身不会迁移域名。

## 本地开发与构建

```bash
pnpm install --frozen-lockfile
pnpm docs:dev
pnpm docs:build
pnpm -C docs docs:preview

# 网页版 + 官网 + 同域旧应用兼容页
pnpm build:site
```

主题继续使用 `vitepress-theme-yunlefun`。产品首页的食材交互和手机演示位于 `docs/.vitepress/theme/components`；菜谱通过 VitePress 数据加载器读取仓库 CSV。产品正文、默认推荐和服务信息在构建时渲染到 HTML，不依赖 JavaScript 才能看到；手机演示由访问者主动播放。

`pnpm build:site` 先运行现有网页版构建，再构建文档，最后由 `scripts/compose-product-site.mjs` 输出 `dist-site`。VitePress 提供官网根路径和文档路由。组合脚本沿用 Nuxt 资源、登录回调、同域旧版设置 / 数据导出路径及退役 service worker，并写入官网自己的 Apple 关联文件。未找到的文档返回文档 404。

组合产物中的 `/about/` 和 `/about/terms.html` 是兼容页面，通过 HTML meta refresh 跳到 `/` 与 `/terms`，同时提供手动链接和标准地址；这不是服务器 301，且不会携带原查询参数。网页版 `dist` 中的审核页面保持不变。EdgeOne Git 部署读取项目根目录的 `edgeone.json`；登录回调的 `no-store` / `no-referrer` 等共享响应头维护在该源配置中，不依赖构建输出内的配置被再次发现。

## 生产项目

### 官网：EdgeOne `yunlefun-cook`

- 项目 ID：`pages-izqbljwk91wt`，Git 仓库 `YunYouJun/cook`，生产分支 `main`。
- 根目录：`./`。
- 构建命令：`pnpm run build:site`。
- 输出目录：`dist-site`。
- 自定义域：`cook.yunle.fun`。

合并前先验证独立预览。项目原配置为 `pnpm run build` / `dist`；切换时必须同时更新命令和输出目录。回滚时可在平台重新发布上一个成功部署；只有回滚到迁移前代码时，才配合恢复旧构建配置。

### 网页版：EdgeOne `yunyoujun-cook`

- 项目 ID：`pages-d3ggorpihuk6`，Git 仓库 `YunYouJun/cook`。
- 2026-11-22 从控制台核对，生产环境关联 `web-production` 且自动部署开启。其他分支自动预览关闭，按需在控制台新建预览部署。
- 框架预设：`Other`，根目录：`./`。
- Node.js：`24.18.0`；安装命令：`pnpm install --frozen-lockfile`。
- 构建命令：`pnpm run build`，输出目录：`dist`。
- 自定义域：`cook.yunyoujun.cn`，加速区域保持全球可用区（含中国大陆）。

2026-10-26 在原 Upload 项目的「项目设置 → Git 管理」中关联了已有授权的 GitHub 仓库，保留项目 ID、域名、证书和部署历史。控制台实际支持该关联操作；不需要删除项目或迁移域名。

GitHub Release 工作流在正式版标签（如 `v2.0.1`）通过版本校验并发布附件后，只把该标签对应的 `main` 提交快进到 `web-production`。EdgeOne 构建该分支，工作流轮询线上构建信息，确认版本和 SHA。请从 `main` 打标签，日常开发合并到 `main`，预发布标签只生成 Release 附件。

切换时从当前线上 SHA 创建 `web-production`，在 `yunyoujun-cook` 的「设置 → 环境管理 → 生产 → 编辑」选择该分支并保存。若 EdgeOne 构建失败且分支已指向该标签，在「构建部署」重新部署该提交，再重跑 GitHub 部署任务核对。站点回滚可在 EdgeOne 重新发布上一个成功部署。

Git 部署使用根目录 `edgeone.json`，其中配置了授权回调的 `no-store` 和 `no-referrer` 响应头。`scripts/lib/web-build.mjs` 将该配置复制到 `dist`，使下载的发布产物也能用于直接上传。官网的 `dist-site` 不应发布到此项目，网页版和审核中的 `/about/` 保持独立。

2026-11-22，`cook.yunyoujun.cn` 的生产构建 `00bc9b1cb` 已提供 Android Chrome 的打开 App、复制和手动全选回调入口。用 Android 15 Chrome 和合成回调验证了剪贴板复制、模拟剪贴板拒绝后全选完整链接，以及向 `fun.yunle.cook` 发出打开请求。线上 `/auth/callback?platform=native` 返回 200 和默认缓存头，未命中配置的两个响应头。`/auth/callback/?platform=native` 返回了配置的响应头。

### iOS Universal Links

构建为两个生产域名生成各自的 `/.well-known/apple-app-site-association`。官网 `cook.yunle.fun` 从 `docs/.vitepress/apple-app-site-association.json` 生成关联，登记 `/recipes/share` 和 `/recipes/share/`，两种路径均要求非空 `id`。网页版 `cook.yunyoujun.cn` 使用 `apps/web/public/.well-known/apple-app-site-association`，负责原生登录回跳、微信授权和共享网页凭据，菜谱链接由网页打开。更新后可用 iOS「备忘录」长按两种官网链接验证系统分流。 [Apple 验证说明](https://developer.apple.com/documentation/technotes/tn3155-debugging-universal-links)

### Android App Links

在两个 EdgeOne 生产项目的构建环境中设置 `COOK_ANDROID_LINKS_SHA256`，使用实际分发的 `fun.yunle.cook` 安装包签名证书的 SHA-256 指纹。多个发布证书可用逗号分隔，每个指纹由 32 个冒号分隔的两位十六进制数构成。通过 Google Play 分发时使用 Play 应用签名证书。

直装指纹可从正式签名 APK 或签名方导出的公开证书取得。从 Google Play 分发时，在 Play Console 的「Play 保护 → Play 商店保护 → 管理 Play 应用签名」找到「应用签名密钥证书」的 SHA-256。若下载了公开证书，可以和直装 APK 一起提取。命令只读取指定文件，输出可填入构建变量的指纹。[Android App Links 官方说明](https://developer.android.com/training/app-links/faq)

```bash
node scripts/qa/verify-android-app-links.mjs --print-fingerprints --apk /path/to/direct.apk --certificate /path/to/play-app-signing.der
```

只发布一个渠道时传入对应的 APK 或公开证书即可。也可以重复传入 `--apk` 或 `--certificate`，工具会合并并去除重复指纹。

如果手机已安装实际分发版，也可以连接设备，设置 `ANDROID_HOME` 指向 Android SDK，再从已安装的 `fun.yunle.cook` 提取指纹。用 `adb devices -l` 查设备序列号。仅连接一台设备时可省略 `--serial`。工具会读取基础 APK，核对包名和签名，完成后删除临时副本。[Android Debug Bridge](https://developer.android.com/tools/adb)

```bash
node scripts/qa/verify-android-app-links.mjs --device --serial SERIAL --print-fingerprints
```

省略 `--print-fingerprints` 后，同一命令会用设备上的 APK 核对两个正式域名的关联文件。

网页版构建只从本次传入的指纹生成 `dist/.well-known/assetlinks.json`，官网组合构建将同一文件带入 `dist-site`。未设置变量的构建会省略关联文件。发布后检查两个域名都通过 HTTPS 直接返回 HTTP 200 和 `application/json`，并包含实际安装包的证书指纹。支持动态 App Links 的 Android 15 及以上设备还会用关联文件约束路由。带尾斜杠和不带尾斜杠的菜谱分享路径都需要非空的 `id`，缺失或留空时由网页承接，应用打开后再校验菜谱编号。账号回调需要 `platform=native` 和受支持的原生授权片段。 [Android 动态规则](https://developer.android.com/training/app-links/configure-assetlinks)

可从仓库根目录运行下列命令同时检查两站。使用 `ANDROID_HOME` 指向 SDK 或传入 `--build-tools`。工具先核对 APK 包名并验签，再核对两站直接发布的证书关联和动态规则。测试 Google Play 渠道时传入从 Play 安装的 APK。

验包会拒绝标记为可调试的 APK，也会拒绝使用 Android Debug 证书签名的 APK 或单独提供的 Android Debug 证书。直接使用 `COOK_ANDROID_LINKS_SHA256` 时，指纹来源以实际分发渠道为准。

```bash
node scripts/qa/verify-android-app-links.mjs --apk /path/to/installed.apk
```

未指定设备、APK 或公开证书时，工具将 `COOK_ANDROID_LINKS_SHA256` 视为完整发布清单，核对两站证书集合是否完全一致。遗漏某个渠道的证书或保留已退出分发的证书都会使校验失败。指定安装包或设备时，可只验收该渠道的证书。

2026-11-23 使用两张临时 X.509 证书执行 `pnpm build:site`，网页版和官网组合产物生成了字节一致的关联文件。通过本地 HTTP 验证，完整清单可通过两站校验，单站多出一张证书时会指出出错域名，按单一渠道的证书也可独立验收。构建和关联文件共 31 项自动化测试通过。两个正式域名的关联文件复查仍返回 HTTP 404。

同日用实际 APK 核对验包分支。一份与正式包同名的 Debug 构建、一份使用 Android Debug 证书签名的 Release APK、该证书文件和未签名的 Release APK 均被拒绝。使用另一张临时证书签名的 Release APK 可通过本地验包，所得指纹与对应公开证书一致。32 项构建和关联文件测试通过。连接的真机仅查到旧版调试安装，GitHub Releases 的 18 个版本没有 APK 或 AAB 附件，当前云乐坊 Google 账号进入 Play Console 后显示开发者注册页。

补齐菜谱编号规则后，47 项构建和关联文件测试以及 11 项菜谱链接和账号回调测试通过。使用两张临时 X.509 证书执行 `pnpm build:site`，网页和官网组合产物中的关联文件字节一致，均通过本地 HTTP 校验。校验工具能指出仍在使用旧分享规则的域名。

在 Android 12 或更高版本设备上安装正式签名包。可在验收设备上触发重新验证，等系统更新域名状态后运行设备检查。多台设备连接时，使用同一个序列号指定验收设备。[Android 系统验证说明](https://developer.android.com/training/app-links/verify-applinks)

```bash
adb -s SERIAL shell pm verify-app-links --re-verify fun.yunle.cook
node scripts/qa/verify-android-app-links.mjs --verify-device --serial SERIAL
```

设备检查使用已安装 APK 的证书核对两站关联文件，要求两个域名都处于 Android 的 `verified` 状态。它还查询带和不带尾斜杠的菜谱链接、原生登录回调和账号交接回调实际解析到的 Activity。回调使用合成片段，命令只查询路由，不打开链接，也不修改设备的默认打开设置。

随后在设备上点开「番茄炒蛋」的正式分享链接，并从云乐坊发起一次新的浏览器授权，验收真正的回跳与登录结果。使用未安装云食册的设备打开同一菜谱链接，确认浏览器能展示菜谱和做法入口。

菜谱分享页在 Android Chrome 提供「在云食册中打开」，由用户点击打开已安装的 `fun.yunle.cook`。网页菜谱仍在加载、读取失败或没有找到时，此入口仍适用于有效的 Android 菜谱编号。未安装时会留在网页菜谱。手动打开的验收和系统自动验证分别记录。[Chrome 的 Android Intent 链接](https://developer.chrome.com/docs/android/intents)

2026-11-21 在 Android 15 模拟器用临时签名包和两个独立 HTTPS 测试域名验收。两个域名自动进入 `verified` 状态，菜谱链接分别打开「炸洋葱花」和「番茄炒蛋」，授权回调路径交给 App。卸载后菜谱链接由 Chrome 打开。同包名换用另一张签名证书时，两个域名均验证失败。生产域名仍需使用实际分发证书部署关联文件。

同日用 Android Chrome 点击测试页中与当前分享组件一致的链接，安装临时签名包时打开 App 内的「番茄炒蛋」，卸载后打开官网的「蒸蛋羹」网页。检查两个生产域名的关联文件均返回 HTTP 404。

网页回退验收在 Android 15 模拟器手动将测试包设为处理 `cook.yunle.fun` 链接。普通 HTTPS 链接冷启动打开「番茄炒蛋」，应用已在前台时换到「拍黄瓜」。仅从测试包的内置菜谱库移除「蒸蛋羹」后，同一 HTTPS 链接先打开 App，点「在网页查看」后 Chrome 从「番茄炒蛋」切到正式官网的「蒸蛋羹」。

菜谱库加载中、加载失败或缺少菜谱时，Android 会把原分享链接交给浏览器，保留查询参数和片段。原地址会随页面状态恢复。旧状态仅保存菜谱编号或原地址超过 8192 个字符时，使用同一编号的标准地址。

Android 15 模拟器先在全新的 Chrome 会话打开正式分享链接，显示了「蒸蛋羹」和做法入口。安装缺少这道菜的测试包后，手动关联该域名，使用带查询参数和片段的链接进入 App。横竖屏切换后点「在网页查看」，Chrome 打开同一道菜并保留查询参数和片段。268 项 Android 自动化测试通过，其中回归测试核对了交给浏览器的完整地址。[「蒸蛋羹」正式分享页](https://cook.yunle.fun/recipes/share/?id=588)

静态规则匹配到网页版授权回调或无效的菜谱编号时，App 会把原链接交回浏览器。2026-11-22 在断网的 Android 15 模拟器手动关联两个域名，验证有效链接冷启动进入菜谱、网页回调及无效菜谱链接打开 Chrome，以及不完整的原生回调停留在 App。自动化还覆盖了保留已有详情页、回调链接原样交接和拒绝非官方地址。动态规则随正式关联文件发布后，还需在安装正式签名包的设备上核对系统分流。

2026-11-23 使用临时签名的直装 Release APK，在 Android 15 模拟器手动关联两域名后从 Chrome 复测。冷启动收到重复编号的菜谱链接或网页授权回调时，原链接交给 Chrome 后会关闭临时启动的云食册页面。App 已打开时，网页分流仍保留原厨房工作台及已勾选的步骤。Chrome 连续打开两道有效分享菜谱后，横屏按一次返回键即可回到原工作台。319 项 Android 测试通过，Debug 和 Release APK 均构建成功。两个生产域名的关联文件仍返回 HTTP 404。

另用测试包模拟内置菜谱库首次读取失败。收到「番茄炒蛋」的链接后，点击「在网页查看」会拉起 Chrome。该模拟器显示 Chrome 首次使用页，完整目标 URL 由自动化回归核对。返回 App 点重试后，打开了本地的「番茄炒蛋」。网页回归覆盖了读取中、读取失败、菜谱缺失和重试恢复时的 App 入口。

Android 分享面板显示菜名作为预览标题，复制得到可直接打开的 HTTPS 地址。用 Android 15 模拟器断网验收，系统分享面板复制的地址粘贴后与正式「番茄炒蛋」分享链接完全一致。手动关联分享域名后打开「蒸蛋羹」，按一次系统返回键即可回到菜谱库。回归覆盖应用尚未显示时用新分享替换旧链接，以及保留菜谱库作为返回目标。333 项 Android 自动化测试全部通过。[Android 系统分享面板](https://developer.android.com/develop/ui/compose/sharing/send)

2026-11-22 在 Android 15 模拟器上验证从设备读取签名指纹。分别安装了临时签名的直装 APK 和本地 Bundletool 生成的四个分包。两次从设备读取的指纹均与公开测试证书一致，验签后临时 APK 已清除。真机未安装正式包时会提示缺少安装，多台设备连接时需要指定序列号。17 项构建与关联文件回归通过。使用设备来源执行完整检查时，两个正式域名的关联文件仍返回 HTTP 404。

同日用临时签名的直装 Release APK 验证 `--verify-device`。从设备读取的指纹与导出的公开证书一致。手动关联前四个探测链接解析到 Chrome，手动关联后解析到云食册，两个域名仍未通过系统验证，命令退出失败。另用正常的系统链接冷启动打开了「番茄炒蛋」。19 项构建与关联验收测试通过。

基于官网线上提交 `4c4db3257` 准备了本地候选 `135871eac`（`codex/android-share-web-4c4db`）。官网组合构建和 37 项分享页、搜索索引及构建回归通过。Android 15 Chrome 打开候选页后，点击「在云食册中打开」可从临时签名的直装包进入「番茄炒蛋」。未安装 App 时，点击后进入正式官网的「蒸蛋羹」网页。目录缺少有效编号时仍可发起打开，App 会显示找不到菜谱并提供网页入口。链接含重复编号时，网页隐藏打开按钮。候选待发布确认。

### 旧文档域名退役

`docs.cook.yunle.fun` 已通过 YunLeFun 账户中独立的 Cloudflare Worker `cook-docs-redirect` 返回 HTTP 301，目标固定为 `https://cook.yunle.fun`，保留请求路径与查询参数。例如 `/guide/ios-beta?source=legacy` 跳到 `https://cook.yunle.fun/guide/ios-beta?source=legacy`，不统一跳到首页，也不依赖已删除的 Pages 项目。

源码位于 [workers/docs-redirect/index.mjs](https://github.com/YunYouJun/cook/blob/main/workers/docs-redirect/index.mjs)，部署配置位于 [workers/docs-redirect/wrangler.jsonc](https://github.com/YunYouJun/cook/blob/main/workers/docs-redirect/wrangler.jsonc)。配置将 `docs.cook.yunle.fun` 声明为 Custom Domain，关闭 `workers.dev` 入口；Worker 只改写目标协议、主机和端口，不请求 Pages 源站。

从仓库根目录使用已授权的 YunLeFun Cloudflare 账户部署：

```bash
pnpm dlx wrangler@4.152.0 deploy --config workers/docs-redirect/wrangler.jsonc --no-bundle
```

Worker 使用 Custom Domain 绑定旧域名，由 Cloudflare 为该绑定自动管理独立的 TLS 证书。区域免费 Universal SSL 的 `*.yunle.fun` 证书不覆盖更深一级的 `docs.cook.yunle.fun`，不能把旧 Pages 证书或一级通配符证书当作迁移后的 HTTPS 保障；必须等待 Worker 自定义域证书生效并实际验证。

**迁移记录（2026-10-25）：** App Store Connect 的 `zh-Hans` App 信息隐私政策地址已从 `https://cook-docs.pages.dev/privacy` 更新为 `https://cook.yunle.fun/privacy`，并通过 API 回读确认。iOS 版本的营销地址 `https://cook.yunle.fun/` 与支持地址 `https://support.yunle.fun/?product=cook` 已核验，无需修改。TestFlight 的 `zh-Hans` 隐私政策与营销地址也已分别更新为 `https://cook.yunle.fun/privacy` 和 `https://cook.yunle.fun/guide/ios-beta`，并回读确认。GitHub 仓库主页已更新为 `https://cook.yunle.fun/`。

- Worker 版本 `d1701c13-3653-4a90-8803-38aa8ddfddcd` 已部署，Custom Domain 与权威 DNS 均已切换到独立 Worker。
- 独立托管证书状态为 active，实际 TLS 证书 SAN 包含 `docs.cook.yunle.fun`，本次回读的有效期截止到 `2027-01-23 08:51:07 GMT`。证书由 Cloudflare 自动续期，不依赖旧 Pages。
- 删除前，18 项 HTTP / HTTPS 路径、查询参数、编码路径及不存在路径检查均返回准确的 301 与 `Location`；4 个官网目标页面返回 200。浏览器验证最终地址为 `https://cook.yunle.fun/guide/ios-beta?source=legacy#ios`，查询参数与片段均保留。
- 因 Cloudflare 的项目删除限制，先清理 311 个历史部署，再删除 `cook-docs` 项目；17:03（Asia/Shanghai）项目列表回读确认该项目已不存在。17:06 删除后命令行复测 `/privacy`，curl、Googlebot、bingbot 三种 User-Agent 均得到 301。
- 随后浏览器对根路径、隐私政策、协议、内测说明、CLI 文档、`.html` 地址、带查询参数与片段的指南、不存在路径及编码路径完成 9 项复测，每次附加唯一查询参数以避开旧重定向缓存。最终地址均为官网且完整保留路径、查询参数和测试片段；不存在路径与编码测试路径显示官网 404 标题。这组浏览器结果验证最终 URL 与页面标题，不据此推断中间 HTTP 状态。
- 复测期间，本机命令行连接旧域名及其他 Cloudflare 主机出现 TLS reset，同时浏览器带新查询参数的访问正常。本记录保留这一访问差异，不推断原因，也不将浏览器成功表述为所有命令行连接均正常。

维护与复验顺序：

1. 先验证官网的隐私政策、用户协议、使用指南和内测说明页已上线，HTTPS、内容与路径正确。
2. 配置独立重定向服务及旧域名的 DNS / TLS。原绑定为 `CNAME docs.cook → cook-docs.pages.dev`；迁移后不能继续依赖这个 Pages 源站或它的证书。保留旧域名和有效证书，让历史链接继续可用。
3. 核验 HTTP / HTTPS 的根路径、`/privacy`、`/terms`、`/guide/ios-beta`、`/dev/cli`、带查询参数及编码路径的请求均返回正确 301 和 `Location`，没有循环；不存在的路径也原样跳转，由官网返回 404。
4. 将 App Store Connect、TestFlight 和微信开放平台中实际保存的旧文档 URL 更新为官网地址并回读。尤其检查 `https://cook-docs.pages.dev/privacy`：历史验收记录曾将其写入 App Store Connect，旧自定义域的重定向不能兼容这个提供商地址。已有网页版审核页和 Universal Links 继续使用原地址。
5. 确认独立重定向已生效、平台不再使用旧 Pages 地址后，再解绑旧 Pages 自定义域并删除 `cook-docs` 项目。删除后复测旧域名的 301 与官网页面。

移除旧的 `docs/public/CNAME`，不把它改成官网域名。官网由 EdgeOne 自定义域配置管理，不需要 GitHub Pages 的 CNAME 文件；避免把旧部署标记复制到新的产物或绑定无关的 GitHub Pages 根站点。

## 迁移约束与验收

原 `cook.yunle.fun` 的浏览器存储不会自动跟随用户到另一个域名。保持 `/home` 和 `/settings` 可访问，并在[域名与原有数据](/guide/web-domain)说明导出、导入过程；不要清除旧记录。登录回调依赖同源 sessionStorage 中的 PKCE 事务，不向另一域名转发授权码或 hash。

检查以下结果后再确认发布完成：

- 新官网首屏、食材选择、播放 / 暂停、真实截图、手机布局和深浅色模式正常。
- 不运行 JavaScript 时仍能读到应用介绍、协议、联系信息及真实发布状态。
- `/guide/getting-started`、`/guide/ios-beta`、`/privacy`、`/terms` 可访问，中文搜索可用。
- 原域名 `/home`、`/settings`、`/auth/callback`、`/.well-known/apple-app-site-association` 和 `/sw.js` 保留。
- `cook.yunyoujun.cn` 的网页版、`/about/` 与 `/about/terms.html` 保持可用。
- 旧文档域名在删除 Pages 项目后仍按原路径与查询参数返回 301，隐私政策及体验报名链接最终可访问。
- 平台资料中的文档链接已改为官网；历史 Pages 部署记录只作为证据，不再作为填写指南。

`yunle.fun` 网站页脚使用该域名在云乐坊官网公开的备案号；不要复制 `yunyoujun.cn` 的个人域名备案号。网站备案不等于 App 备案或微信能力审核通过。只有取得实际 App Store / TestFlight 安装链接并决定开放后，才更新下载入口。
