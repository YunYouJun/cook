# Web v2.0.0 正式版准备

本轮发布对象为 Web「食用手册」。根目录与 `apps/web/package.json` 统一使用 `2.0.0`。原生 iOS「云食册」继续通过 TestFlight 测试，Web 正式版不代表 App Store 上架，也不改变 iOS 分发范围。

## 发布说明草稿

- 按食材、厨具、忌口与关键词找菜，支持经典选菜和工作台两种布局。
- 保存收藏、历史、私人菜谱与个人食材厨具；会员收藏和历史可通过云乐坊账号同步。
- 按经审核的饮食目标、隔夜带饭与机构低 GI 场景筛选，查看菜谱依据与适用范围。
- 首页增加可关闭的 iOS TestFlight 提示，关闭后在当前浏览器保持隐藏；“关于”页保留安装与反馈入口。

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
