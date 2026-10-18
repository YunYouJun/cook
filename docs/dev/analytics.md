# Microsoft Clarity 网站统计

Cook 使用现有 Microsoft Clarity 项目，提供访问分析、热力图、会话回放和关键点击事件，无需部署统计服务器或数据库。

- 项目：**Cook**
- 网站：`https://cook.yunyoujun.cn`
- Project ID：`ks1ebw03i5`（公开标识，不是管理密钥）
- [仪表板](https://clarity.microsoft.com/projects/view/ks1ebw03i5/dashboard)
- [项目设置](https://clarity.microsoft.com/projects/view/ks1ebw03i5/settings#overview)

## 配置与发布

默认配置已经指向上述项目。分叉部署者应填写自己的项目 ID 和域名：

```dotenv
NUXT_PUBLIC_CLARITY_PROJECT_ID=ks1ebw03i5
NUXT_PUBLIC_CLARITY_DOMAINS=cook.yunyoujun.cn,cook.yunle.fun
```

显式设置空项目 ID 可关闭统计。开发模式、未授权域名、原生 Capacitor WebView、Do Not Track 和 Global Privacy Control 开启的浏览器不加载统计。页面就绪后异步加载 Clarity，不代理或打包第三方脚本。

静态托管须在**构建前**设置配置，执行 `pnpm build` 并发布 `dist`。容器启动后修改环境变量不会改变静态资源。Docker 支持同名 `--build-arg`，GitHub Docker 工作流读取同名 Repository variables，未设置时使用 Cook 默认值。工作流中需要禁用时可将项目 ID 设置为 `disabled`，它无法通过 ID 校验。

原 Google Tag Manager 配置已移除；不再加载 Umami。前端切换不会取消已有 Vercel 或数据库资源的账单。

旧版 PWA 的 Workbox 可能持续返回 2025 年缓存页面。`public/sw.js` 保留原脚本地址，接管后注销旧 worker 并刷新受控页面，不删除 localStorage、IndexedDB 或用户菜谱。即使关闭 PWA 模块，也不要删除这个迁移脚本，否则老访客可能无法收到新页面和统计代码。

## 事件与回放

| 事件          | 操作           |
| ------------- | -------------- |
| `click_stuff` | 选择或取消食材 |
| `click_tool`  | 选择或取消厨具 |
| `click_dish`  | 打开菜谱做法   |

事件仅发送固定名称，不附带用户 ID、邮箱、自由输入、食材名称或自建菜谱内容。Clarity 的事件 API 不接收 Umami 风格的事件属性；访问和 SPA 页面分析由 Clarity 自身采集，不再手动补发 PV。

`body` 设置 `data-clarity-mask="true"`，遮罩回放中的文本，包括个人资料和自建菜谱。遮罩不是禁止所有数据采集：Clarity 仍会接收页面 URL、交互及技术信息。URL 中不要携带个人内容；项目设置中还应配置 URL 查询参数遮罩（如 `ticket`、`code`、`state`、`access_token`）。

直接进入 `/auth` 或携带常见凭证参数的页面时不加载脚本。已加载的页面导航到这类 URL 前调用 Clarity `stop`，并在本次文档生命周期内停止业务事件；重新加载普通页面后恢复。脚本加载或事件发送失败不会阻断 Cook 操作。

代码不调用 `identify`，也不会替访客发送“已同意”的信号。有同意管理需求的部署应连接真实的用户选择；Clarity 对 EEA、英国、瑞士的访问有同意信号要求，未提供时部分功能受限。站点隐私说明应包含实际使用的统计服务。

## 验收

1. 部署后在允许的生产域名访问首页，确认 `https://www.clarity.ms/tag/ks1ebw03i5` 只加载一次。
2. 检查 Clarity 采集请求成功，再在后台确认收到访问和三个业务事件；只有脚本下载成功不代表数据已经入库。
3. 切换页面、选择食材、打开菜谱，验证功能正常；阻断 Clarity 域名后重复验证。
4. 查看一条回放，确认账号和自建内容被遮罩；登录回调入口不应加载脚本。
5. 中国大陆访问效果需要用目标运营商网络实测，不能用本机可访问代替全部用户的可达性结论。

Clarity 官方当前提供免费服务。普通回放保留 30 天，标记或收藏的会话及热力图可保留 9 个月；单项目每日最多保存 10 万条会话回放，超过后可能抽样。它适合行为分析，不替代长期原始事件仓库。

参考：[定价](https://clarity.microsoft.com/pricing)、[客户端 API](https://learn.microsoft.com/en-us/clarity/setup-and-installation/clarity-api)、[保留周期](https://learn.microsoft.com/en-gb/clarity/data-retention)、[Cookie 与同意信号](https://learn.microsoft.com/en-us/clarity/setup-and-installation/clarity-cookies)。
