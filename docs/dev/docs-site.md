# 官网、文档与网页版部署

## 域名分工

| 地址                  | 用途                                          | 部署产物               |
| --------------------- | --------------------------------------------- | ---------------------- |
| `cook.yunle.fun`      | 云食册产品官网、iOS 发布信息和使用 / 开发文档 | `dist-site`            |
| `cook.yunyoujun.cn`   | 食用手册网页版                                | `dist`                 |
| `docs.cook.yunle.fun` | 旧文档入口，保持原文档路径可访问              | `docs/.vitepress/dist` |

文档和产品首页共用 `docs` 中的一份 VitePress 源码、导航和搜索，标准地址（canonical）与 sitemap 使用 `https://cook.yunle.fun`。`docs.cook.yunle.fun` 兼容旧链接，不维护第二套内容。微信开放平台已经提交的 `https://cook.yunyoujun.cn/about/` 及其用户协议保留可用，审核期间不因本次域名分工而删除。

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

## 生产项目

### 官网：EdgeOne `yunlefun-cook`

- 项目 ID：`pages-izqbljwk91wt`，Git 仓库 `YunYouJun/cook`，生产分支 `main`。
- 根目录：`./`。
- 构建命令：`pnpm run build:site`。
- 输出目录：`dist-site`。
- 自定义域：`cook.yunle.fun`。

合并前先验证独立预览。项目原配置为 `pnpm run build` / `dist`；切换时必须同时更新命令和输出目录。回滚时可在平台重新发布上一个成功部署；只有回滚到迁移前代码时，才配合恢复旧构建配置。

### 网页版：EdgeOne `yunyoujun-cook`

网页版使用独立的 Upload 项目，输出仍为 `dist`。官网改版不应把 `dist-site` 上传到此项目，也不应覆盖网页版或审核中的 `/about/`。现有版本发布流程仍按仓库 release workflow 执行。

### 旧文档域名：Cloudflare Pages `cook-docs`

继续复用 `cook-docs` 项目，生产分支 `main`，输出 `docs/.vitepress/dist`。`docs.cook.yunle.fun` 的自定义域及 `CNAME docs.cook → cook-docs.pages.dev` 保留；`docs/public/CNAME` 记录这一兼容域，实际绑定由平台和 DNS 管理。

旧域名暂时提供同一份官网 / 文档构建，并指向新域名的 canonical。完成新官网上线验证后，可以再设置逐路径永久重定向；不要在新域名还没有文档内容时提前切走隐私政策、体验报名等链接。

## 迁移约束与验收

原 `cook.yunle.fun` 的浏览器存储不会自动跟随用户到另一个域名。保持 `/home` 和 `/settings` 可访问，并在[域名与原有数据](/guide/web-domain)说明导出、导入过程；不要清除旧记录。登录回调依赖同源 sessionStorage 中的 PKCE 事务，不向另一域名转发授权码或 hash。

检查以下结果后再确认发布完成：

- 新官网首屏、食材选择、播放 / 暂停、真实截图、手机布局和深浅色模式正常。
- 不运行 JavaScript 时仍能读到应用介绍、协议、联系信息及真实发布状态。
- `/guide/getting-started`、`/guide/ios-beta`、`/privacy`、`/terms` 可访问，中文搜索可用。
- 原域名 `/home`、`/settings`、`/auth/callback`、`/.well-known/apple-app-site-association` 和 `/sw.js` 保留。
- `cook.yunyoujun.cn` 的网页版、`/about/` 与 `/about/terms.html` 保持可用。
- 旧文档隐私政策及体验报名链接仍可访问。

`yunle.fun` 网站页脚使用该域名在云乐坊官网公开的备案号；不要复制 `yunyoujun.cn` 的个人域名备案号。网站备案不等于 App 备案或微信能力审核通过。只有取得实际 App Store / TestFlight 安装链接并决定开放后，才更新下载入口。
