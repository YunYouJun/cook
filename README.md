# Cook

> 好的，今天我们来做菜 🥬

- 网站链接：[cook.yunyoujun.cn](https://cook.yunyoujun.cn)
- 备用：[cook.yyj.moe](https://cook.yyj.moe)

## 参考

- [隔离食用手册大全](https://docs.qq.com/sheet/DZUpJS0tQZm1YYWlt)

## 说明

本项目初衷是方便特殊时期隔离在家而材料有限的小伙伴，因此菜谱材料会尽量限制在特定范围内。

更多可参见 [来做菜 | 关于](https://cook.yunyoujun.cn/about)。

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

You can either choose to pull the image from docker hub or build your own one locally.

### pull image from docker hub

```bash
# pull the image from docker hub
docker pull yourname/cook:dev
# start the container, then open http://localhost:3333
docker run -it -d --name cook_dev -p 3333:3333 henryclw/cook:dev
```

### local build

```bash
# build the image locally
docker build . -t ourname//cook:dev
# start the container, then open http://localhost:3333
docker run -it -d --name cook_dev -p 3333:3333 henryclw/cook:dev
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

<p align="center">
  <a href="https://cdn.jsdelivr.net/gh/YunYouJun/sponsors/public/sponsors.svg">
    <img src='https://cdn.jsdelivr.net/gh/YunYouJun/sponsors/public/sponsors.svg'/>
  </a>
</p>
