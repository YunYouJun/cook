# 注册来源记录与发布

Cook 在文档初次进入时记录来源。`firstTouch` 保存在当前域名的 localStorage，30 天后重新采集；`registerTouch` 保存在当前标签的 sessionStorage，每次文档进入更新。SPA 内跳页不会把原落地渠道改成站内页面。`/auth/*` 回调不采集，以免把统一登录、GitHub 或微信授权页计入获客来源。存储被禁用时，本次文档以内存降级，来源失败不会阻止登录。

可读存储被清空或损坏时，以当前存储状态为准，不能从旧内存恢复已删除的来源。访问存储被拒绝时使用经过校验的内存上下文；若已知新写入失败且存储仍保留完全相同的旧值，使用当前文档的新来源，防止旧活动回流。可观察的删除或值变化会清除此降级状态。首次与本次入口超过 30 天均失效，不长期保留旧活动。

来源只携带落地域名、路径、外部 referrer 域名以及 `utm_source`、`utm_medium`、`utm_campaign`。`yunle.fun`、其全部子域、`cook.yunyoujun.cn` 和当前域名的站内跳转不会计入外部 referrer，避免 SSO 或 Apps 往返被统计成获客渠道。查询、hash 和凭证不会写入来源记录。没有 referrer/UTM 表示来源缺失，不能据此断言为直接访问。

登录前读取同一份 `{ version: 1, firstTouch, registerTouch }`，同时覆盖 Web 顶层重定向、Apps host bridge 和 Cook 原生浏览器。来源为客户端上报数据；统一登录服务依据 Registry 验证应用入口并依据 Auth 判断账号是否真正新注册，不能按 Cook 首次登录或公开资料创建时间判断。后续登录不会覆盖账号已保存的注册来源。

## 本地联调

新协议在 `@yunlefun/sso@0.7.0`。发布前，用本地 `YunLeFun/ylf` checkout 的实际构建/打包产物验证：

```sh
node scripts/qa/verify-acquisition-sdk.mjs --sdk-root=/path/to/ylf
# 可选：同时验证当前应用的构建与类型（包括已有工作区改动）
node scripts/qa/verify-acquisition-sdk.mjs --sdk-root=/path/to/ylf --build --typecheck
```

脚本构建 SDK 并生成临时 tarball，短暂切换已有 node_modules 的 SSO 软链接，运行定向测试和 lint，最后恢复旧软链接。它不修改 package.json、lockfile 或 pnpm override，也不发布 npm。运行时避免并发安装依赖。

## 发布顺序

1. 先发布账户私有来源集合/写入 API 与统一登录、SSO 服务端（它们可先接收可选字段并继续兼容旧客户端）。
2. 从 `YunLeFun/ylf` 发布已验证的 `@yunlefun/sso@0.7.0`；该版本给现有 PKCE 协议增加可选来源字段。
3. 在 Cook 解析并更新 0.7.0 的 lockfile，复核保留当前其他依赖改动，再执行 frozen install、typecheck 和构建并发布。
4. 发布 Apps 的来源透传代码；原生“使用其他账号”分支还需要新的 iOS/Android 容器版本。

本改动只准备代码和本地验证，未发布 npm 或部署应用。Cook 原锁文件的 `@yunlefun/sso@0.6.2` 记录刻意保留：0.7.0 发布前不能生成其可信 registry integrity，当前 manifest/lockfile 尚不能执行 frozen install。不要伪造 integrity、改写本地路径或提交临时 override。

历史用户最多保留来自 SSO 时间关联的应用入口推断；不能把 GA 总体流量比例回填为某个 UID 的外部渠道。各域名、不同浏览器和被清除的存储也无法恢复此前未采集的触达。
