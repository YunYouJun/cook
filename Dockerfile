FROM node:lts-alpine

RUN apk update
RUN apk add xdg-utils

RUN npm install -g pnpm

WORKDIR /app

COPY . .

RUN pnpm install

# convert csv to json
# automatically executed when postinstall
RUN pnpm convert

EXPOSE 3333

ENTRYPOINT ["pnpm", "dev"]
