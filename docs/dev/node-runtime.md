# Node.js 版本约定

Cook 与 YunLeFun/admin 的开发和 CI 统一使用 Node.js `24.22.2`，以各仓库 `.nvmrc` 为版本来源。GitHub Actions 的 `setup-node` 读取该文件；Cook Docker 构建镜像固定为 `node:24.22.2-alpine`。两个仓库的直接 `@types/node` 依赖使用 24 系列。

根包 `engines.node` 为 `^24.11.0`，兼容平台提供的 Node 24 补丁版本，并限制工具链主版本。`fnm use` 或 `nvm use` 可切换到仓库指定的补丁版本，不需要修改整台机器的默认版本。

EdgeOne 构建固定为平台已预装的 `24.18.0`，通过 `edgeone.json` 的 `nodeVersion` 声明。该值控制构建环境，线上 SSR／函数运行时仍由托管平台管理。不同平台使用各自支持的 Node 24 补丁版本，不能将构建版本当作运行时升级回执。

## CloudBase 生产迁移

CloudBase 的目标为 `Nodejs24.11`。2026-11-03 已通过临时 Event Function 实测：实际 Node 为 `24.11.1`，预打包的 `@cloudbase/node-sdk@3.18.3`、CloudBase AI 模型实例与内置 fetch 均可初始化。没有执行模型推理或业务写入，临时函数已删除。

目前生产的 `cook-ai-intent`、`cook-user-data`、`cook-feedback`、`cook-feedback-test` 仍为 `Nodejs18.15`。修改源码部署配置不会升级已有函数的运行时；平台运行时在创建时固定，迁移需要重建。正式迁移前保留当前部署配置，避免把目标版本误当作已完成的升级。

按函数逐个执行受控替换，保留部署包、环境变量、入口、超时、内存、角色、网络、权限和路由；已有 SDK 调用名和 HTTP 地址继续复用。`cook-feedback` 的私有 Support SDK 使用现有部署包，避免混入 SDK 版本变化。新运行时使用预打包依赖并关闭云端安装；超过 ZIP 直传大小限制的包通过管理 SDK 的 COS 上传方式发布。

生产替换需安排可接受的维护窗口，并在每个函数恢复可用后再继续。失败时用原部署包和配置恢复旧运行时。迁移涉及函数 ID 和版本资源变化，不能按普通代码更新执行。Admin 的两个定时函数与完整步骤见 [Admin Node 运行时迁移说明](https://github.com/YunLeFun/admin/blob/main/docs/node-runtime-upgrade.md)。

依据：[Node.js 24.22.2 LTS](https://nodejs.org/en/blog/release/v24.22.2)、[CloudBase 运行时](https://docs.cloudbase.net/cloud-function/runtime-support)、[运行时不可原地修改](https://cloud.tencent.com/document/api/583/18580)。
