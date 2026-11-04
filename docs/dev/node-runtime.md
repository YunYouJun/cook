# Node.js 版本约定

Cook 与 YunLeFun/admin 的开发和 CI 统一使用 Node.js `24.22.2`，以各仓库 `.nvmrc` 为版本来源。GitHub Actions 的 `setup-node` 读取该文件；Cook Docker 构建镜像固定为 `node:24.22.2-alpine`。两个仓库的直接 `@types/node` 依赖使用 24 系列。

根包 `engines.node` 为 `^24.11.0`，兼容平台提供的 Node 24 补丁版本，并限制工具链主版本。`fnm use` 或 `nvm use` 可切换到仓库指定的补丁版本，不需要修改整台机器的默认版本。

EdgeOne 构建固定为平台已预装的 `24.18.0`，通过 `edgeone.json` 的 `nodeVersion` 声明。该值控制构建环境，线上 SSR／函数运行时仍由托管平台管理。不同平台使用各自支持的 Node 24 补丁版本，不能将构建版本当作运行时升级回执。

## CloudBase 生产迁移

CloudBase 使用 `Nodejs24.11`。2026-11-04 临时 Event Function 完成 Node 24 创建、SDK 与 fetch 初始化、测试隔离、定时器恢复，以及 Node 18 回滚验证；实际 Node 为 `24.11.1`，临时函数已删除。没有执行模型推理或业务写入。

2026-11-04 已逐个重建 `cook-ai-intent`、`cook-user-data`、`cook-feedback`、`cook-feedback-test` 并核验为 `Active / Nodejs24.11`。部署文件内容、环境变量、角色、网络、权限、默认别名和 HTTP 路由均保留。函数 SDK 的未登录拒绝、HTTP 方法与请求校验、测试反馈的环境隔离均通过。本次使用线上原包迁移，业务源码及菜谱词典保持此前线上版本。

新运行时使用预打包依赖并关闭云端安装。`cloudbaserc.json` 已移除 `node_modules` 忽略规则，发布目录必须包含所需生产依赖；不要将未安装依赖的源码目录直接部署。AI 和用户数据函数应在独立临时目录按各自 `package.json` 安装生产依赖，再打包发布；反馈函数使用 `scripts/prepare-support-feedback.mjs` 生成包含经校验私有 Support SDK 的独立包及部署配置。完成后回收临时目录。超过 ZIP 直传大小限制的包通过管理 SDK 的 COS 上传方式发布。

后续普通代码发布应使用代码更新接口并保留线上环境变量，不将仓库中的安全默认值 `COOK_AI_ENABLED=false` 覆盖到已启用环境。运行时在创建时固定；再次跨运行时迁移仍需备份并逐个重建，不能按普通代码更新执行。创建参数必须按 CloudBase 请求结构逐层映射，不能回填查询响应中的 `EipAddress` 等只读字段。Admin 的请求映射器、两个定时函数和完整回执见 [Admin Node 运行时迁移说明](https://github.com/YunLeFun/admin/blob/main/docs/node-runtime-upgrade.md)。

依据：[Node.js 24.22.2 LTS](https://nodejs.org/en/blog/release/v24.22.2)、[CloudBase 运行时](https://docs.cloudbase.net/cloud-function/runtime-support)、[运行时不可原地修改](https://cloud.tencent.com/document/api/583/18580)。
