# iOS 云乐坊会员 DeepSeek

关联 [#181](https://github.com/YunYouJun/cook/issues/181)，实现位于 `cloudfunctions/cook-ai-intent` 与原生 `ManagedCookAIService`。服务地址为 `https://api.yunle.fun/cook-ai-intent`，部署于云乐坊现有上海环境 `yunlefun-8g7ybcxc7345c490`。不改变其他云乐坊 AI 产品的计费或权益。

## 数据流与资格

1. 原生以现有云乐坊账号的短期 AccessToken 检查服务能力，不提交用户编号、会员布尔值或个人 DeepSeek Key。
2. 服务通过固定的 CloudBase `/auth/v1/user/me` 验证身份，再以同一用户令牌调用 `/v1/functions/account-api` 的 `getAccount`，复用账号限制和会员规则。拒绝匿名、不可用账号、非会员与过期会员；不使用广权限的账户内部令牌。
3. 每次解析都重新核验会员有效期，再原子预留配额。等待配额后、调用模型前再次检查会员是否到期。
4. 用户确认发送本次文本后才请求模型。平台模型凭据仅位于服务端；返回严格的 `SearchIntent`，客户端再按本机数据、可用目标和本地明确条件校验，确认后才检索内置菜谱。

原生资格缓存最长 60 秒，且不超过会员到期时间。缓存绑定账号和会话版本，退出或更换账号即失效；迟到的资格或解析响应不能覆盖当前状态。缓存到期会阻止下一次请求，已发起的请求仍按账号、会话和用户选择核对，不因缓存计时结束而丢弃有效结果。个人 Key 路径独立保留，无需会员。

## 额度与保存

当前试运行配置为每位会员每天 10 次、每分钟 3 次，全服务每天最多 100 次。每天按北京时间零点划分，模型每次最多输出 2048 token，文本最多 1000 字。这是有输入／输出上限的请求预算，不是人民币金额预算。未配置完整限额、凭据或服务开关时保持不可用。

`cook_ai_quota` 是独立的 `ADMINONLY` 集合。用户标识由服务端密钥生成 HMAC 摘要，仅记录时间窗口、请求数和过期时间；全局、用户日及用户分钟计数在同一事务更新。只重试已知事务冲突，达到限额或存储异常时不调用模型。请求已准入后，上游失败或超时也不退回额度，避免不确定结果导致预算超用。

日计数三天、分钟计数两分钟后过期。`expiresAt` 为 Date，并建有普通查询索引；这不是自动 TTL。后续成功准入的会员解析每次最多查询、复核并删除 100 条过期记录，无新请求时会保留到后续清理。没有新增定时任务。输入原文、健康诉求、原始模型响应、账号令牌和模型 Key 不进入数据库或日志；诊断只采用固定错误分类。

## 配置与发布

云函数为 Event Function，经现有 HTTP Access 网关暴露，运行时 `Nodejs18.15`，超时 45 秒。函数 SDK 调用沿用环境的 `auth != null` 默认规则；HTTP 请求仍必须通过函数内的用户令牌及会员校验。没有修改整个环境的其他函数安全规则。

服务端变量：

| 名称                           | 用途                           |
| ------------------------------ | ------------------------------ |
| `COOK_AI_ENABLED`              | 仅字面值 `true` 才启用         |
| `COOK_AI_DEEPSEEK_API_KEY`     | 服务端专用 DeepSeek 凭据       |
| `COOK_AI_QUOTA_SALT`           | 至少 32 字符的安全随机摘要密钥 |
| `COOK_AI_USER_DAILY_LIMIT`     | 用户日请求限额                 |
| `COOK_AI_USER_MINUTE_LIMIT`    | 用户分钟请求限额               |
| `COOK_AI_DAILY_REQUEST_BUDGET` | 全服务日请求预算               |

凭据通过云函数配置注入，不写仓库或客户端。更新代码使用单函数代码更新，保留已有环境变量；修改配置时读取、合并现有值，不将仓库中的安全默认关闭配置直接覆盖到已启用环境。关闭开关可立即停止新模型调用，本地识别与个人 Key 不受影响。

原生的 `COOK_AI_SERVICE_URL` 构建设置默认指向已核验的业务地址，可显式覆盖；置空会关闭会员通道。仅允许指定 HTTPS 地址，无重定向、Cookie 或缓存。正常会员请求超时 45 秒，资源超时 50 秒；客户端取消会取消传输，已到达服务器的请求仍可能消耗一次预留额度。

服务词典不由客户端提供。更新菜谱后运行：

```bash
pnpm convert
node scripts/generate-ai-intent-contract.mjs
node scripts/generate-ai-intent-contract.mjs --check
node --test test/cookAiIntent.test.mjs test/aiIntentContract.test.mjs
```

提交生成的 `contract.json` 后与客户端一起发布。Swift 的跨端测试会核对完整词典、版本和真实开放目标；新客户端数据与服务器不匹配时返回 `VERSION_MISMATCH`，不消耗模型配额。

## SDK 选择

可以通过 SwiftPM 使用社区 [SwiftOpenAI](https://github.com/jamesrochabrun/SwiftOpenAI#deepseek) 或 [MacPaw/OpenAI](https://github.com/MacPaw/OpenAI)。二者支持 OpenAI 兼容服务，适合后续多模型或流式扩展。

当前会员接口是云乐坊业务接口，只有一次受限 JSON 请求；模型 SDK 无法替代会员核验、额度和账号切换隔离。因此继续复用原生 URLSession 账号层与严格传输策略，不额外引入模型 SDK。CloudBase 的[官方 iOS Swift 指南](https://docs.cloudbase.net/quick-start/frameworks/ios-swift)同样使用 HTTP API。

## 2026-10-29 验证

后端单元测试、原生单元测试及部署记录在本次 PR 中维护。实际生产验收使用平台既有、状态可用的受管会员测试身份，只建立短期登录会话，未调用会员调整或账本写入接口。真实会员能力检查为 HTTP 200，真实 DeepSeek 解析为 HTTP 200，返回的鸡蛋、不辣及减少添加糖均通过严格校验；测试会话已注销。首次模型输出因缺少规范示例未通过校验，补充完整 JSON 示例后重验通过，未放宽任何字段规则。

在线拒绝验证包括：未登录与无效令牌返回 HTTP 401、伪造身份字段返回 HTTP 400、非 POST 请求返回 HTTP 405，均符合约定。非会员、过期会员、账号限制、并发额度、超时、非法响应与迟到响应通过注入测试覆盖，不能据此宣称每个拒绝场景都已在生产账号上执行。

服务端与生成词典共 20 项 Node 测试通过，词典检查和限定文件 ESLint 通过。CookCore 全量测试及词典修正后的定向复验合计 88 项通过、1 项既有预期跳过；原生 AI、饮食会话、账号与 SSO 共 67 项测试通过，含最终会员适配器与设置的 21 项定向复验。复用现有 iOS 27 的 iPhone 18 Pro 与 iPad Pro 13-inch (M5)，标准字号浅色及最大辅助字号深色流程均有通过记录。还修复了搜索建议打开编辑页时同时清空搜索造成的呈现竞争，修复后的 iPhone 编辑／取消／确认和 iPad 两种模式均通过复验。实际 VoiceOver 朗读与新 TestFlight 分发仍待完成。
