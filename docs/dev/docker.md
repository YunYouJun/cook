# Docker 部署

Docker 部署的是 Cook 网页应用，使用 Nginx 提供构建后的静态文件。支持 Docker Compose、服务器 IP 加端口和独立域名反向代理；访问页面不需要设置域名白名单或启动 Nuxt 开发服务器。

## 使用 Compose

安装 Docker Engine 和 Compose 插件后，从普通 Git 克隆目录构建：

```bash
git clone https://github.com/YunYouJun/cook.git
cd cook
docker compose up -d --build
docker compose ps
```

打开 `http://localhost:8080`。默认映射 `127.0.0.1:8080:80`，适合本机访问或宿主机反代。需要直接通过服务器 IP 访问时：

```bash
COOK_HOST=0.0.0.0 COOK_PORT=8080 docker compose up -d --build
```

也可以在根目录 `.env` 写入 `COOK_HOST` 和 `COOK_PORT`。端口映射变更后重新执行 `docker compose up -d`，Compose 会重建容器。停止并移除容器使用 `docker compose down`。

镜像构建需要 Git 命令与完整可读的 HEAD，以生成 `/.well-known/cook-build.json`。Dockerfile 已安装 Git；构建上下文保留必要 Git 元数据，排除依赖、构建缓存、本地 `.env` 和签名文件。源码 ZIP 或指向宿主机路径的 linked worktree 不满足此要求，请另建普通克隆目录构建。最终 Nginx 镜像仅复制静态产物和服务配置。

## 独立域名反向代理

部署到独立域名的根路径，例如 `https://cook.example.com/`。应用资源使用根路径，不要只将 `/cook/` 子路径代理到容器。

宿主机 Nginx 的 HTTP 配置示例（放在 `http` 内的站点配置中）：

```nginx
server {
    listen 80;
    server_name cook.example.com;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

HTTPS 可由已有网关终止 TLS，或由 Caddy 自动申请证书。以下 Caddyfile 使用同一个本机端口：

```text
cook.example.com {
    reverse_proxy 127.0.0.1:8080
}
```

域名需解析到服务器，Caddy 自动申请证书还需公网能访问该服务器的 80 / 443 端口。若代理运行在另一个容器中，将它接入 Cook 所在的 Compose 网络，使用 `cook:80` 作为上游；代理容器内的 `127.0.0.1` 指向代理自身。

容器内 Nginx 已为客户端路由提供 `index.html` 回退，直接打开或刷新 `/recipes/favorites`、`/cookbooks/new`、`/auth/callback` 等路径也能加载应用；`/_nuxt/` 下不存在的资源保持 404。目录跳转使用相对地址，避免外部 HTTPS 被重定向到容器的 HTTP 地址或内部端口。

## 构建参数与云服务

端口与反代本身不需要 `NUXT_HOST`、`NUXT_PORT` 或 `allowedHosts`。它们用于开发服务器，当前容器提供的是静态文件。

Compose 支持在构建时传入统计配置，例如关闭统计：

```bash
NUXT_PUBLIC_CLARITY_PROJECT_ID= docker compose up -d --build
```

`NUXT_PUBLIC_CLARITY_DOMAINS` 可设置自己的域名；分叉部署应使用自己的 Clarity 项目。显式留空项目 ID 会关闭统计。详情见[网站统计](/dev/analytics)。

静态应用的 `NUXT_PUBLIC_*` 配置在构建时写入资源。`docker run -e ...`、Compose 的 `environment` 或只重启容器不能改变已生成的配置。其他公开配置可通过 Dockerfile 的构建参数按需接入，再重建镜像。

自建域名能访问页面，不代表已接通云乐坊登录、同步或反馈服务。默认登录回调仅登记官方域名，自建部署需要匹配的 SSO 客户端、回调登记与服务端授权；仅修改反代 Host 或添加环境变量不能完成登记。本地选菜功能不依赖这些服务。

## 更新与排查

源码部署更新：

```bash
git pull --ff-only
docker compose up -d --build
docker compose logs --tail=100 cook
```

不需要在容器内执行 `pnpm fetch` 或挂载数据卷。静态更新不会清除浏览器本机数据；切换域名或协议会改变浏览器存储的来源，请先在原地址导出需要保留的数据。

若使用 Docker Hub 的 `yunyoujun/cook:latest`，执行 `docker pull yunyoujun/cook:latest` 并重新创建容器。该镜像由版本标签触发发布，主分支修复不会立即更新 `latest`；需要当前修复时使用上述源码构建。

排查时分别访问容器端口与反代域名，检查首页、深层路由和浏览器网络面板中的实际 `/_nuxt/` 资源。直接端口可用而域名失败时，核对 DNS、代理上游、根路径映射与 TLS；出现深层路由 404 时，先确认运行的镜像包含本页描述的 Nginx 配置。可用 `docker compose exec cook nginx -t` 检查配置，用 `/.well-known/cook-build.json` 核对实际构建版本。

仓库提供真实容器 HTTP 冒烟验证，使用任务专属容器和网络，并在结束时回收：

```bash
docker compose -p cook-smoke build
node scripts/test-docker.mjs cook-smoke-cook
docker compose -p cook-smoke down --rmi local
```

该检查需要干净的 Git 提交，覆盖端口直连与第二层 Nginx 反代、客户端路由、目录跳转、实际脚本、缺失资源和构建版本；通过请求头模拟 HTTPS 终止后的转发，不申请证书或修改公网服务。
