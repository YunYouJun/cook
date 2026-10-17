# Cook

> 好的，今天我们来做菜 🥬
> Note: This is primarily a Chinese project and we do not intend to translate to English due to the fact that all the ingredients we are familiar with are in Chinese.

## 经典版存档（archive/v1）

本分支是 v1.2.4 经典网页版的存档，部署于 [v1.cook.yunyoujun.cn](https://v1.cook.yunyoujun.cn)，不再更新。新版请访问 [cook.yunyoujun.cn](https://cook.yunyoujun.cn)。

- 存档站与新版是不同域名（不同 origin），浏览器本地保存的收藏、历史、已选食材等数据**不会**迁移到新版。
- 已移除 GTM 统计、PWA/Service Worker 与 `server/` 接口；页面带 `noindex` 与指向新版的 canonical。
- 构建（纯静态）：`pnpm install && pnpm build:static`（pnpm 9.15.4，Node 20+，本地以 Node 24 验证），产物目录 `.output/public`。

## 版本

[![Release](https://github.com/YunYouJun/cook/actions/workflows/release.yml/badge.svg)](https://github.com/YunYouJun/cook/actions/workflows/release.yml)

### 网页版本

- 网站链接：[cook.yunyoujun.cn](https://cook.yunyoujun.cn)
  - 国内加速：[cook.yunle.fun](https://cook.yunle.fun)
- 备用：[cook.yyj.moe](https://cook.yyj.moe)
- 开发版：[cook.yunle.app](https://cook.yunle.app)

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
  - [居家菜谱投稿](https://docs.qq.com/form/page/DWk9GWW9oTmlXZU9V)
  - [反馈建议分享-兔小巢](https://support.qq.com/products/507827)

### Features

本项目支持 PWA，使用浏览器打开时，可将其添加到主屏幕以获得近原生 APP 的体验。

## 开发

```bash
# install dependencies
pnpm install

# convert csv to json
# automatically executed when postinstall
pnpm convert

# start
pnpm dev
# http://localhost:3333
```

## 部署

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

## [Sponsors](https://sponsors.yunyoujun.cn)

感谢至今以来的所有赞助者们！因为你们的支持让我更有动力去做各种尝试。

<p align="center">
  <a href="https://cdn.jsdelivr.net/gh/YunYouJun/sponsors/public/sponsors.svg">
    <img src='https://cdn.jsdelivr.net/gh/YunYouJun/sponsors/public/sponsors.svg'/>
  </a>
</p>
