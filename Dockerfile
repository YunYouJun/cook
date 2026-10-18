FROM node:24-alpine AS builder

RUN apk update
RUN npm install -g pnpm@11.21.0

WORKDIR /app
COPY . .

# Public analytics settings are compiled into the static site.
ARG NUXT_PUBLIC_CLARITY_PROJECT_ID=ks1ebw03i5
ARG NUXT_PUBLIC_CLARITY_DOMAINS=cook.yunyoujun.cn,cook.yunle.fun

RUN pnpm install --frozen-lockfile && pnpm run build

FROM nginx:stable-alpine
COPY --from=builder /app/dist /usr/share/nginx/html
EXPOSE 80
