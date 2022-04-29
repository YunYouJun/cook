# Cook

> 好的，今天我们来做菜 🥬

- 网站链接：[cook.yunyoujun.cn](https://cook.yunyoujun.cn)
- 备用：[cook.yyj.moe](https://cook.yyj.moe)

## 参考

- [隔离食用手册大全](https://docs.qq.com/sheet/DZUpJS0tQZm1YYWlt)

## 说明

本项目初衷是方便特殊时期隔离在家而材料有限的小伙伴，因此菜谱材料会尽量限制在特定范围内。

更多可参见 [来做菜 | 关于](https://cook.yunyoujun.cn/about)。

欢迎反馈更多菜谱数据：

- [菜谱数据](https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS)
  - [新菜谱反馈](https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS?tab=uykkic)
  - [晒晒你的菜](https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS?tab=dmeahc)
  - [反馈建议](https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS?tab=snaau2)

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

## docker

你可以选择从 Docker Hub 拉取最新的镜像，或本地自行构建。

### 从 Docker Hub 拉取最新的镜像

```bash
# 从 Docker Hub 拉取最新的镜像
docker pull yunyoujun/cook:latest
# 启动容器，然后打开 http://localhost:3333
docker run -it -d --name cook_dev -p 3333:3333 yunyoujun/cook:latest
```

### 自己本地构建

```bash
# 本地构建
docker build . -t yourname/cook:localdev
# 启动容器，然后打开 http://localhost:3333
docker run -it -d --name cook_dev -p 3333:3333 yourname/cook:localdev
```

## 致谢

感谢以下小伙伴为本项目提供的数据支持和 QA ！

- [Runny](https://weibo.com/runny)
- 山竹太凉
- leo
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
