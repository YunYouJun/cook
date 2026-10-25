# 官网、文档与网页版部署

## 域名分工

| 地址                  | 用途                                          | 部署产物                   |
| --------------------- | --------------------------------------------- | -------------------------- |
| `cook.yunle.fun`      | 云食册产品官网、iOS 发布信息和使用 / 开发文档 | `dist-site`                |
| `cook.yunyoujun.cn`   | 食用手册网页版                                | `dist`                     |
| `docs.cook.yunle.fun` | 旧文档入口，按原路径永久重定向到官网          | 独立重定向服务，无站点构建 |

文档和产品首页共用 `docs` 中的一份 VitePress 源码、导航和搜索，标准地址（canonical）与 sitemap 使用 `https://cook.yunle.fun`。App、公开页面、README 和平台资料中的文档链接统一使用官网。旧文档域名只承担历史链接兼容，不再部署第二份内容。微信开放平台已经提交的 `https://cook.yunyoujun.cn/about/` 及其用户协议保留可用，审核期间不因本次域名分工而删除。

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

`pnpm build:site` 先运行现有网页版构建，再构建文档，最后由 `scripts/compose-product-site.mjs` 输出 `dist-site`。该脚本不修改两个输入目录：VitePress 占用官网根路径和文档路由，保留 Nuxt 资源、登录回调、同域旧版设置 / 数据导出路径、AASA 及退役 service worker。未找到的文档返回文档 404，不使用应用壳兜底。

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

网页版使用独立的 Upload 项目，输出仍为 `dist`。`scripts/copy-web-output.ts` 将根目录 `edgeone.json` 一并复制到上传产物，使授权回调的 `no-store` / `no-referrer` 等响应头随直接上传生效。官网改版不应把 `dist-site` 上传到此项目，也不应覆盖网页版或审核中的 `/about/`。现有版本发布流程仍按仓库 release workflow 执行。

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
