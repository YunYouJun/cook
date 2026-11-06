# iOS 云乐坊会员 DeepSeek

关联 [#181](https://github.com/YunYouJun/cook/issues/181)，实现位于 `cloudfunctions/cook-ai-intent` 与原生 `ManagedCookAIService`。服务地址为 `https://api.yunle.fun/cook-ai-intent`，部署于云乐坊现有上海环境 `yunlefun-8g7ybcxc7345c490`。不改变其他云乐坊 AI 产品的计费或权益。

## 数据流与资格

1. 原生以现有云乐坊账号的短期 AccessToken 检查服务能力，不提交用户编号、会员布尔值或个人 DeepSeek Key。
2. 服务通过固定的 CloudBase `/auth/v1/user/me` 验证身份，再以同一用户令牌调用 `/v1/functions/account-api` 的 `getAccount`，复用账号限制和会员规则。拒绝匿名、不可用账号、非会员与过期会员；不使用广权限的账户内部令牌。
3. 每次解析都重新核验会员有效期，再原子预留配额。等待配额后、调用模型前再次检查会员是否到期。
4. 用户确认发送本次文本后才请求模型。平台模型凭据仅位于服务端；返回严格的 `SearchIntent`，客户端再按本机数据、可用目标和本地明确条件校验，确认后才检索内置菜谱。

原生资格缓存最长 60 秒，且不超过会员到期时间。缓存绑定账号和会话版本，退出或更换账号即失效；迟到的资格或解析响应不能覆盖当前状态。缓存到期会阻止下一次请求，已发起的请求仍按账号、会话和用户选择核对，不因缓存计时结束而丢弃有效结果。个人 Key 路径独立保留，无需会员。

## 额度与保存

当前试运行配置为每位会员每天 10 次、每分钟 3 次，全服务每天最多 100 次。每天按北京时间零点划分，模型每次最多输出 2048 token，文本最多 1000 字。这是有输入／输出上限的请求预算，不是人民币金额预算。未配置完整限额、所选通道需要的凭据或服务开关时保持不可用。两条模型通道共用配额，切换不会重置请求预算。

`cook_ai_quota` 是独立的 `ADMINONLY` 集合。用户标识由服务端密钥生成 HMAC 摘要，仅记录时间窗口、请求数和过期时间；全局、用户日及用户分钟计数在同一事务更新。只重试已知事务冲突，达到限额或存储异常时不调用模型。请求已准入后，上游失败或超时也不退回额度，避免不确定结果导致预算超用。

日计数三天、分钟计数两分钟后过期。`expiresAt` 为 Date，并建有普通查询索引；这不是自动 TTL。后续成功准入的会员解析每次最多查询、复核并删除 100 条过期记录，无新请求时会保留到后续清理。没有新增定时任务。输入原文、健康诉求、原始模型响应、账号令牌和模型 Key 不进入数据库或日志；诊断只采用固定错误分类。

## 配置与发布

云函数为 Event Function，经现有 HTTP Access 网关暴露，运行时 `Nodejs24.11`，超时 45 秒。生产依赖随部署包预打包，关闭云端依赖安装。函数 SDK 调用沿用环境的 `auth != null` 默认规则；HTTP 请求仍必须通过函数内的用户令牌及会员校验。没有修改整个环境的其他函数安全规则。

平台默认通道通过部署环境变量配置，云函数启动时解析为不可变快照；运行时不查询通道配置数据库，也没有轮询或缓存到期刷新。修改部署配置并发布后由新实例使用新值，发布期间已经开始的请求按原实例的快照完成；App 不需要重新发版。

| 名称                                  | 用途                                                     |
| ------------------------------------- | -------------------------------------------------------- |
| `YUNLEFUN_AI_DEFAULT_PROVIDER`        | 云乐坊默认文字模型通道：`deepseek`（默认）或 `cloudbase` |
| `YUNLEFUN_AI_DEFAULT_DEEPSEEK_MODEL`  | 直连默认型号，默认 `deepseek-flash`                      |
| `YUNLEFUN_AI_DEFAULT_CLOUDBASE_MODEL` | CloudBase 默认型号，默认 `deepseek-v4.1-flash`           |
| `COOK_AI_ENABLED`                     | Cook 服务开关，仅字面值 `true` 才启用                    |
| `COOK_AI_DEEPSEEK_API_KEY`            | 仅直连通道必需的服务端凭据                               |
| `COOK_AI_QUOTA_SALT`                  | 至少 32 字符的安全随机摘要密钥                           |
| `COOK_AI_USER_DAILY_LIMIT`            | 用户日请求限额                                           |
| `COOK_AI_USER_MINUTE_LIMIT`           | 用户分钟请求限额                                         |
| `COOK_AI_DAILY_REQUEST_BUDGET`        | 全服务日请求预算                                         |

平台变量优先于旧的 `COOK_AI_PROVIDER`、`COOK_AI_DEEPSEEK_MODEL`、`COOK_AI_CLOUDBASE_MODEL`；没有配置平台变量时继续兼容旧部署，再使用内置默认值。显式空值或非法值关闭模型服务，不静默回退。服务开关、Key 与所有业务额度仍由 Cook 自己配置，客户端不能覆盖通道、型号或调用地址。

### 统一默认通道与部署范围

三个 `YUNLEFUN_AI_DEFAULT_*` 变量是云乐坊共享部署约定。环境变量属于各服务或云函数，设置 Cook 的变量不会自动修改其他业务；统一发布时须把同一组平台变量注入所有继承默认通道的服务，并分别确认部署成功。CMS、ADV.JS、Chat Runtime 已有的显式型号、能力和计费策略继续由各业务管理，不因默认通道改变而被覆盖。

这次尚未上线的 Admin 动态保存入口、配置 API、数据库读取和 30 秒缓存已撤回。后台不能再通过保存数据库文档切换线上通道，也不把通道开关替换为即时修改生产环境变量的按钮。详细约定见 `YunLeFun/admin/docs/ai-provider-settings.md`。

通道配置不再依赖 `platform_ai_settings` 或 `platform_ai_settings_audit`。此前准备的两个平台私有集合未写入配置，当前源码不再访问；本次没有修改或删除线上资源。会员身份与 `cook_ai_quota` 的配额读写照常保留，零查询仅指模型通道配置。

### 配置与切换步骤

在部署平台或 CloudBase 控制台中为目标云函数设置下列变量。仅修改本机 `.env` 不会同步远端云函数；仅更新函数代码也不会把本机环境变量自动注入已有配置。

官方直连：

```dotenv
YUNLEFUN_AI_DEFAULT_PROVIDER=deepseek
YUNLEFUN_AI_DEFAULT_DEEPSEEK_MODEL=deepseek-flash
```

保留现有 `COOK_AI_DEEPSEEK_API_KEY`，费用由 DeepSeek 官方账户承担。

CloudBase 托管：

```dotenv
YUNLEFUN_AI_DEFAULT_PROVIDER=cloudbase
YUNLEFUN_AI_DEFAULT_CLOUDBASE_MODEL=deepseek-v4.1-flash
```

也可以将托管型号设为已启用的 `deepseek-v4.1-flash-1017`。函数使用 `@cloudbase/node-sdk` 与固定 `cloudbase` 分组，复用业务环境 `yunlefun-8g7ybcxc7345c490` 的 Event Function 身份，无需额外管理员 Key 或 DeepSeek Key。上线前核对型号已启用且环境有适用额度；托管模型按 CloudBase 规则结算，详见[Node SDK 接入](https://docs.cloudbase.net/ai/model/nodejs-access)。

发布时读取并合并现有云函数配置，只修改这三个通道变量，保留 Key、摘要密钥、服务开关与请求预算。通过配置更新／发布使新实例加载新值，再验收会员能力、一次有效解析与对应通道用量。只更新代码时使用单函数代码更新，保留已有环境变量，不将仓库中 `COOK_AI_ENABLED=false` 的安全默认配置覆盖到已启用环境。回滚时恢复此前变量快照并重新发布；全业务统一切换不具有跨服务原子性。

两条通道分别配置型号，只校验所选通道，型号名称限定为 `deepseek-*`。CloudBase 模式不受未配置 DeepSeek Key 影响。发布尚未完成时不能依据本地配置或后台页面认定线上已切换。

两条通道均关闭思考、请求 JSON、限制输出为 2048 token，并使用同一份提示词及完整输出校验。CloudBase 只允许一次生成，不执行 SDK 注册的工具；截断、工具调用、超大或非法响应均拒绝。`source=deepseek` 表示模型来源，保持现有客户端协议。每次调用使用 20 秒模型传输超时，不自动重试或切换到另一通道，上游失败仍消耗已预留的一次配额。模型凭据、账号令牌和原始错误不会返回客户端。

原生的 `COOK_AI_SERVICE_URL` 构建设置默认指向已核验的业务地址，可显式覆盖；置空会关闭会员通道。仅允许指定 HTTPS 地址，无重定向、Cookie 或缓存。正常会员请求超时 45 秒，资源超时 50 秒；客户端取消会取消传输，已到达服务器的请求仍可能消耗一次预留额度。

服务词典不由客户端提供。更新菜谱后运行：

```bash
pnpm convert
node scripts/generate-ai-intent-contract.mjs
node scripts/generate-ai-intent-contract.mjs --check
node --test test/cookAiIntent.test.mjs test/aiIntentContract.test.mjs
```

提交生成的 `contract.json` 后与客户端一起发布。Swift 的跨端测试会核对完整词典、版本和真实开放目标；服务器未收录的客户端版本返回 `VERSION_MISMATCH`，不消耗模型配额。

### 已分发版本的契约兼容

`contract.json` 跟随当前共享数据生成；`compatible-contracts.json` 保存仍在使用的已分发版本的完整契约，不随生成器更新。服务只按请求的 `schemaVersion`、`policyVersion`、`dataVersion` 精确选择这两处已核验词典，提示词与输出校验使用同一份选定契约。客户端不能上传词典或扩大可用目标；不同版本共用既有会员资格和配额。

首份保留契约为 `faf919fc0041`，来自 2026-11-06 下载的生产函数，并与已归档 TestFlight 构建 12 的数据版本核对。原始 `contract.json` SHA-256 为 `721abeb4b3e70a7b68001af2fe3af0528024d46a99928ffb31d36c6b82f08f6e`；对象的紧凑 JSON SHA-256 为 `7f63acad75d3e4952bc80b82e319940532751e49c3e7a7c1d0ceeb1aa8378926`，由回归测试固定。该版本仅开放减少添加糖和均衡搭配，不能收到新版独有的食材、控制体重目标或响应版本。

兼容列表必须存在且有效，不能重复注册同一个版本；最多同时保留当前及四个历史契约。损坏或歧义配置关闭模型入口，未收录版本在预留额度前拒绝。上线新版前先核对实际分发构建，保留仍需服务的旧契约，再执行单函数代码更新；不要直接覆盖旧契约后要求所有已安装客户端同时升级。移除历史契约需另行核对分发与支持范围。

2026-11-02 的饮食数据 v2 开放「控制体重」后，契约同步提供 `weight-aware`。生成器保留已审核目标白名单，并要求每个开放目标至少 10 道通过记录。模型仅能将用户明确的「控制体重」翻译为份量和额外烹调油参考；减肥、减脂、低热量和疾病饮食仍列待确认，不生成营养数值或减重结论。客户端与后端须使用同一新版契约发布；本轮源码测试不代表生产云函数已更新。

## SDK 选择

可以通过 SwiftPM 使用社区 [SwiftOpenAI](https://github.com/jamesrochabrun/SwiftOpenAI#deepseek) 或 [MacPaw/OpenAI](https://github.com/MacPaw/OpenAI)。二者支持 OpenAI 兼容服务，适合后续多模型或流式扩展。

当前会员接口是云乐坊业务接口，只有一次受限 JSON 请求；模型 SDK 无法替代会员核验、额度和账号切换隔离。因此继续复用原生 URLSession 账号层与严格传输策略，不额外引入模型 SDK。CloudBase 的[官方 iOS Swift 指南](https://docs.cloudbase.net/quick-start/frameworks/ios-swift)同样使用 HTTP API。

## 2026-11-03 双通道源码验证

后续契约兼容修复已于 2026-11-06 发布，当前及构建 12 的真实会员请求均已验证；实际通道为 CloudBase 托管 `deepseek-v4.1-flash-1017`。当前状态与首次发布失败记录见[智能找菜收尾](./ios-search-intent#_2026-11-06-契约与发布验收收尾)，下列记录保留其对应日期的范围。

后端与生成词典共 31 项 Node 测试通过。覆盖共享部署变量优先级、旧环境变量兼容、非法配置关闭服务、CloudBase 免 DeepSeek Key、身份与会员准入、共享配额、非法输出、超时和限频错误。实际入口通过隔离模块运行验证：启动后修改注入变量不改变旧实例通道，新实例使用新值；通道配置不访问数据库，解析只访问配额集合。失败不重试或切换通道。

此前使用实际的 `@cloudbase/node-sdk@3.18.3` 和传输替身验证了 `cloudbase` 分组、两种型号、JSON 与 token 参数、20 秒传输配置、响应解析及 429 映射。该检查未执行 CloudBase 远程推理。

### 2026-11-03 生产发布

主线提交 [`01a58757`](https://github.com/YunYouJun/cook/commit/01a587578ff6189de1fba7633d5c376a2703fd1a) 已通过单函数代码更新发布，状态为 `Active`。生产包四个源文件已下载并逐字节核对；环境变量、运行时、超时、内存和依赖安装配置保持原值，继续使用 DeepSeek 官方直连 `deepseek-flash`。本次未合入开发分支上的菜谱／iOS 功能，生产契约仍为 `faf919fc0041`。

复用受管生产会员测试号完成能力检查与一次「鸡蛋，不吃辣」解析，均为 HTTP 200，结果通过契约及条件核对。未登录、无效令牌为 401，GET 为 405。CloudBase 通道已具备部署配置能力，本次未切换或执行其远程推理验收。完整回执见[发布证据](./evidence/ai-provider-deployment-2026-11-03.json)。

Admin 的 `/cloudbase/functions` 当前支持查看函数配置和更新超时、内存、依赖安装配置；前端没有环境变量编辑或 Git 重部署入口。后续如需后台触发发布，应接固定的 owner 部署流程，校验提交、保留现有凭据并记录发布结果；本次没有新增该入口。

## 2026-10-29 验证

后端单元测试、原生单元测试及部署记录在本次 PR 中维护。实际生产验收使用平台既有、状态可用的受管会员测试身份，只建立短期登录会话，未调用会员调整或账本写入接口。真实会员能力检查为 HTTP 200，真实 DeepSeek 解析为 HTTP 200，返回的鸡蛋、不辣及减少添加糖均通过严格校验；测试会话已注销。首次模型输出因缺少规范示例未通过校验，补充完整 JSON 示例后重验通过，未放宽任何字段规则。

在线拒绝验证包括：未登录与无效令牌返回 HTTP 401、伪造身份字段返回 HTTP 400、非 POST 请求返回 HTTP 405，均符合约定。非会员、过期会员、账号限制、并发额度、超时、非法响应与迟到响应通过注入测试覆盖，不能据此宣称每个拒绝场景都已在生产账号上执行。

服务端与生成词典共 20 项 Node 测试通过，词典检查和限定文件 ESLint 通过。CookCore 全量测试及词典修正后的定向复验合计 88 项通过、1 项既有预期跳过；原生 AI、饮食会话、账号与 SSO 共 67 项测试通过，含最终会员适配器与设置的 21 项定向复验。复用现有 iOS 27 的 iPhone 18 Pro 与 iPad Pro 13-inch (M5)，标准字号浅色及最大辅助字号深色流程均有通过记录。还修复了搜索建议打开编辑页时同时清空搜索造成的呈现竞争，修复后的 iPhone 编辑／取消／确认和 iPad 两种模式均通过复验。实际 VoiceOver 朗读与会员账号的 TestFlight 真机操作仍待维护者验收。

### TestFlight 2.0.0 (10)

2026-10-29 已完成仅内部测试分发，Apple Build ID 为 `c31e2a32-7955-44cd-8a95-f5c013637d2c`，回读为 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，沿用既有「内部测试」组。冻结源码 `7c183f00f1c18e72cd314a1c8fee863d638f6056` 整合主线 `620b495` 与 AI 功能 `8c94644`，正式包内的会员 AI／反馈地址、词典版本、隐私资源和 Production 权限均已核对，Apple 包校验通过。中文测试说明已写入并回读；真机会员操作由维护者继续，发布回执不替代该项验收。签名与产物记录见[内部发布流程](./ios-testflight)。
