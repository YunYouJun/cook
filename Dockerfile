FROM node:lts-alpine AS builder

RUN apk update
RUN npm install -g pnpm

WORKDIR /app
COPY . .

RUN pnpm install && pnpm run generate

FROM nginx:stable-alpine
COPY --from=builder /app/.output/public /usr/share/nginx/html
EXPOSE 80
