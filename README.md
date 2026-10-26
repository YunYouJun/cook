# Cook

> 好的，今天我们来做菜 🥬
> Note: This is primarily a Chinese project and we do not intend to translate to English due to the fact that all the ingredients we are familiar with are in Chinese.

## 版本

[![Release](https://github.com/YunYouJun/cook/actions/workflows/release.yml/badge.svg)](https://github.com/YunYouJun/cook/actions/workflows/release.yml)

### 网页版本

- 官网与文档：[cook.yunle.fun](https://cook.yunle.fun)
- 网页应用：[cook.yunyoujun.cn](https://cook.yunyoujun.cn)
- 开发版（Vercel）：[cook.yunle.app](https://cook.yunle.app)

本仓库不再配置 Netlify 自动部署；当前部署配置见[文档站点部署说明](./docs/dev/docs-site.md)。

### 小程序版本

~~本仓库为网页版本，小程序版本请在微信搜索「来做菜」。~~

因不可抗力，小程序因跳转 B 站视频而被判定为导流违规下架。
将不再提供小程序版本。

<!-- ![微信小程序版本](./public/search-cook.png) -->

## 说明

本项目初衷是方便特殊时期隔离在家而材料有限的小伙伴，因此菜谱材料会尽量限制在特定范围内。

更多可参见 [来做菜 | 关于](https://cook.yunyoujun.cn/about)。

欢迎反馈更多菜谱数据：

- 相关链接
  - [菜谱数据表格（飞书）](https://yunlefun.feishu.cn/wiki/KgxowvnB9iM91AkZEJHcNGoQnPg?sheet=X4j9Bn)
  - [居家菜谱投稿](https://docs.qq.com/form/page/DWk9GWW9oTmlXZU9V)
  - [反馈建议分享-兔小巢](https://support.qq.com/products/507827)

### Features

~~本项目支持 PWA，使用浏览器打开时，可将其添加到主屏幕以获得近原生 APP 的体验。~~

我们正在开发新的 APP 版本，敬请期待。

## 开发

**开发文档**：<https://cook.yunle.fun/>

详细的环境配置、飞书数据拉取等说明请查看开发文档。

### 启动项目

```bash
# 安装依赖
pnpm install

# 从飞书拉取最新数据(可选)
pnpm fetch

# 或将本地 CSV 转换为 JSON
pnpm convert

# 启动开发服务器
pnpm dev
# http://localhost:3333
```

### CLI 命令

```bash
# 从飞书拉取菜谱数据
pnpm fetch

# 将 CSV 转换为 JSON
pnpm convert
```

更多 CLI 使用说明请查看 [Cook CLI 文档](https://cook.yunle.fun/dev/cli)。

### 开发 APP

Web 保留 Nuxt + Ionic；原生 iOS 使用 SwiftUI，位于 [`apps/ios`](./apps/ios/README.md)。旧 Capacitor 外壳已移除，原生 Android 将使用 Kotlin + Jetpack Compose，见 [Android 分阶段计划](./docs/dev/android-roadmap.md) 和 [移动应用开发](./docs/dev/app.md)。

## 部署

### 网站统计

使用 Microsoft Clarity，复用 Cook 项目，通过 `NUXT_PUBLIC_CLARITY_PROJECT_ID` 和 `NUXT_PUBLIC_CLARITY_DOMAINS` 配置，无需部署统计服务器或数据库。显式设置空项目 ID 可关闭统计；静态站点须在构建前配置。详见 [网站统计](./docs/dev/analytics.md)。

### Docker

```bash
# 从 Docker Hub 拉取最新的镜像
docker pull yunyoujun/cook:latest
# 新建并启动容器，然后打开 http://localhost:3333
docker run -it -d --name cook -p 8080:80 yunyoujun/cook:latest

# 启动与停止
docker start cook
docker stop cook
```

## 致谢

感谢以下小伙伴为本项目提供的数据支持和 QA ！

- [Runny](https://weibo.com/runny)
- 麒麟
- 晴方啾
- 课代表阿伟

部分图文做法来自 [Anduin2017/HowToCook](https://github.com/Anduin2017/HowToCook)（Unlicense），感谢所有贡献者。

## [Sponsors](https://sponsors.yunyoujun.cn)

感谢至今以来的所有赞助者们！因为你们的支持让我更有动力去做各种尝试。

<p align="center">
  <a href="https://cdn.jsdelivr.net/gh/YunYouJun/sponsors/public/sponsors.svg">
    <img src='https://cdn.jsdelivr.net/gh/YunYouJun/sponsors/public/sponsors.svg' alt='Sponsors'/>
  </a>
</p>
