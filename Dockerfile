FROM node:24.22.2-alpine AS builder

RUN apk add --no-cache git
RUN npm install -g pnpm@11.21.0

WORKDIR /app
COPY . .

# pnpm build verifies HEAD and records its provenance in the static output.
RUN test -d .git || (echo 'Docker builds require a standalone Git clone, not a source ZIP or linked worktree.' >&2; exit 1)

# Public analytics settings are compiled into the static site.
ARG NUXT_PUBLIC_CLARITY_PROJECT_ID=ks1ebw03i5
ARG NUXT_PUBLIC_CLARITY_DOMAINS=cook.yunyoujun.cn,cook.yunle.fun

RUN pnpm install --frozen-lockfile && pnpm run build

FROM nginx:stable-alpine
COPY docker/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
