# Web v2.0.0 正式版准备

本轮发布对象为 Web「食用手册」。根目录与 `apps/web/package.json` 统一使用 `2.0.0`。原生 iOS「云食册」继续通过 TestFlight 测试，Web 正式版不代表 App Store 上架，也不改变 iOS 分发范围。

## 发布说明草稿

- 按食材、厨具、忌口与关键词找菜，支持经典选菜和工作台两种布局。
- 保存收藏、历史、私人菜谱与个人食材厨具；会员收藏和历史可通过云乐坊账号同步。
- 按经审核的饮食目标、隔夜带饭与机构低 GI 场景筛选，查看菜谱依据与适用范围。
- 首页增加可关闭的单行 iOS 测试入口，关闭后在当前浏览器保持隐藏；“关于”页保留完整 TestFlight 安装与反馈入口。
- 饮食与专题集中在右上角“筛选”弹层，首页按需展开食材：手机默认每组最多六种常用项，桌面十二种；收起后仍保留已选食材。

当前 Web 未启用 PWA 离线安装。原生端的冰箱到期提醒、iCloud 和 AI 功能不纳入这份 Web 发布承诺。饮食筛选覆盖审核过的候选集，未提供全库热量、脂肪阈值或实验室 GI 测定。

## iOS 测试引导

使用已有[公开邀请](https://testflight.apple.com/join/KTTJNhDh)及[安装与反馈指南](https://cook.yunle.fun/guide/ios-beta)。需要 iOS 17 / iPadOS 17 或更新版本，名额和可用版本以 TestFlight 为准。页面不承诺具体构建号，避免把内部构建当成公开可安装版本。

2026-11-09 准备时，公开渠道仍为构建 7、上限 30 人；构建 14 为 `INTERNAL_ONLY`，仅面向已有内部组。本轮不修改公开组、名额、邀请、外测审核或 iOS 构建。真机未验证项目继续保留原状态。

## 验证与发布顺序

1. PR 合并前完成 lint、类型检查、Web 生产构建及桌面／手机浏览器验证：选菜、提示关闭及刷新、关于页常驻入口、深浅色、外部链接。检查 CI，保留浏览器证据。
2. 合并后，EdgeOne 从 `main` 自动部署 Web 应用与官网。回读两个域名的 `/.well-known/cook-build.json`，核对版本、合并提交及 `dirty=false`，再验证首页和关于页。
3. 经维护者确认正式发布后，在已验证的合并提交上创建并推送 `v2.0.0`。不要直接运行默认 `pnpm release`，该命令会提交、打标签并推送。
4. 标签触发 GitHub Release 和 Docker 发布：Release 附带完整静态站压缩包及 SHA-256；压缩包须包含 `.well-known/cook-build.json`、`edgeone.json` 和 `index.html`。Docker 正式版生成 `2.0.0`、`2.0`、`latest`；后续预发布标签不覆盖 `latest`。
5. 回读 Release 的正式版状态、附件和校验和，验证 Docker 镜像及两个站点。仅标签成功不等于网站已更新；网站部署以 EdgeOne 与线上构建信息为准。

## 发布后补齐

- 记录最终 PR、合并 SHA、CI、EdgeOne 构建信息、Release 附件哈希与 Docker digest。
- 记录 iOS 公开邀请的可访问状态；测试名额可能随时变化，不在首页写实时剩余人数。
- 保留用户数据导出／导入与旧版入口，继续跟进 Android、视频来源维护及更完整的营养数据。

## 已合并的界面准备

[#213](https://github.com/YunYouJun/cook/pull/213) 已合并为 `7237513a6fba07c9b5e44cc3e4114341d69e761e`，包含紧凑首页、分类标题单色图标、iOS 测试入口及两端定位文档。[PR CI](https://github.com/YunYouJun/cook/actions/runs/42847028046) 与 [main CI](https://github.com/YunYouJun/cook/actions/runs/42851276010) 均通过；合并后两个域名的构建信息已核对为该提交、版本 `2.0.0`、`dirty=false`。这是当次部署记录，后续修复部署需重新回读。

独立旧版介绍页 `/about` 在不带尾斜杠时会把相对样式和图片解析到站点根目录。本轮添加 `/about/` 资源基址，保留页面内容和锚点；Chromium 手机／桌面及 WebKit 手机已验证两种 URL，样式、图片和页内导航均正常。应用内“我的 → 关于”的 TestFlight 入口与官网组合输出的旧路由跳转继续保留。

## 依赖安全收尾（2026-11-11）

2026-11-09 的 125 条为历史快照。本轮重新读取默认分支，实际为 **130 条**：critical 6、high 54、medium 50、low 20，涉及 32 个包。升级使用固定 Node 24.22.2 / pnpm 11.21.0、独立依赖安装和冻结锁文件，保留最短发布等待、来源信任与构建脚本白名单。

| 范围                         | 处理                                                                                                                                               |
| ---------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Web 框架与浏览器依赖         | Nuxt 4.6.4、Vue 3.5.42 以上及兼容范围内的依赖刷新，覆盖 Vite、devalue、seroval、axios、请求解析等告警                                              |
| Git 元数据与测试工具         | simple-git 4.0.2 / argv-parser 2.0.1、shell-quote 修复版、Vitest 4.1.12；oxfmt 的 tinypool 定向至 2.1.2                                            |
| 文档与构建匹配               | glob 14.0.2、minimatch 11.0.5、brace-expansion 5.0.12，保留不受对应告警影响的旧分支；验证 ESLint、类型检查、CLI 与文档构建兼容性                   |
| 数学渲染与预览服务           | micromark-extension-math 的 KaTeX 定向至 0.18.2，serve 的 compression 至 1.8.2                                                                     |
| 未启用的 PWA                 | 移除未使用的构建模块、配置和脚本，随之删除 Workbox 的旧来源信任例外；保留用于退役旧离线缓存的 `public/sw.js`                                       |
| 无可安装上游修复的 deepmerge | 4.3.1 使用 pnpm 补丁拒绝来源与目标中的 `__proto__`，覆盖 CommonJS/UMD 运行入口；8 个漏洞与兼容性回归用例通过，未修补版本的两个对照用例均可复现问题 |

本地 `pnpm audit` 剩 **1 条 low**，critical / high / moderate 均为 0；与本轮原始 Dependabot 告警逐项比对也只剩 deepmerge。该包经 `unbuild` / Rollup 引入构建工具，不作为静态站的 Node 服务运行。补丁已缓解已知问题，但不会改变上游版本号，**不隐藏、不手动忽略这条告警，也不宣称告警清零**。

截至本轮核对，npm audit 虽提示 deepmerge 4.3.2，官方注册表仍只有 4.3.1，[GitHub 公告](https://github.com/advisories/GHSA-gcqx-w48m-w2h4)也未列出修复版。补丁说明、移除条件和上游跟踪见[依赖补丁说明](https://github.com/YunYouJun/cook/blob/main/patches/README.md)。后续默认分支的实时状态以[仓库依赖告警](https://github.com/YunYouJun/cook/security/dependabot)为准。

这轮不推送 `v2.0.0` 正式标签。合并后的 CI、部署回读和告警重算结果应补到本轮 PR；正式标签及 Release / Docker 的验收仍按上面的发布顺序执行。iOS 分发与真机未验证项保持原有边界。
