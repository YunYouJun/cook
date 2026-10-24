# 文档站部署

正式文档地址为 <https://docs.cook.yunle.fun/>。站点复用 Cloudflare Pages 的 `cook-docs` 项目，源仓库为 `YunYouJun/cook`，生产分支为 `main`；PR 和其他分支使用 Pages 预览部署。

## 主题与本地预览

主题使用 `vitepress-theme-yunlefun`，保留 Cook 图标和文档内容。主题入口位于 `docs/.vitepress/theme/index.ts`；站点配置通过 `withYunlefun` 接入，使用中文导航与本地搜索，并保留原有代码组、任务列表和 Twoslash 配置。

```bash
pnpm install --frozen-lockfile
pnpm docs:dev
pnpm docs:build
pnpm -C docs docs:preview
```

生产输出为 `docs/.vitepress/dist`。修改主题或 Markdown 后检查首页、中文搜索、移动端目录、横向表格，以及深浅色模式；不要把构建缓存和输出目录提交到仓库。

## 域名

- 在 Pages 项目的「自定义域」中绑定 `docs.cook.yunle.fun`。
- `yunle.fun` 的 DNS 记录为 `CNAME docs.cook → cook-docs.pages.dev`，由 Cloudflare 管理 DNS 和 HTTPS 证书。
- `docs/public/CNAME` 记录目标域名，实际绑定仍需在 Pages / DNS 中配置，单独修改此文件不会创建域名。
- sitemap 使用 `https://docs.cook.yunle.fun`；原 `cook-docs.pages.dev` 保留为平台地址。

变更合并后，以 Pages 生产部署成功、自定义域状态正常、HTTPS 无证书错误且以下页面可访问为完成标准：

- [文档首页](/)
- [云食册 iOS 内测](/guide/ios-beta)
- [隐私政策](/privacy)

外测尚未开放时，内测说明保留「准备中」状态。只有取得实际邀请链接并决定开放后，才添加加入入口。
