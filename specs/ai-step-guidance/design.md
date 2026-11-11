# 设计

## 界面

- 用途：在做菜时解释当前原文步骤，帮助用户理解并自行继续。
- 视觉：沿用已批准的 iOS 原生 SwiftUI 工作台及现有颜色、字体、间距和层级，不引入独立品牌、聊天 tab 或悬浮助手。项目现有设计系统优先于通用 UI 技能的默认配色/字体禁令。
- 布局：当前步骤原文及确定性用量优先；独立的讲解/提问按钮在其后展开局部讲解区，必要时使用工作台内 sheet。iPhone 单列，iPad 保持现有宽屏结构；长回答可滚动，大字体不遮挡步骤操作。
- 状态：idle → loading → answer / needs-source / error；取消及上下文变化返回 idle。单次本步问答，不设计无限聊天列表。引用原文可在讲解旁展开核对。

`RecipeStepCard` 当前整张卡片是勾选完成的 Button。AI 操作必须放在该 Button 外，防止嵌套交互导致讲解时误勾选。沿用现有步骤与进度标识；AI 状态独立于 `KitchenWorkbench` 持久化模型。

## 模块边界

先核对实际结构，再用少量新类型表达教学能力：独立 `CookStepGuidance` 请求/响应契约、上下文构建和校验、provider 协议、个人 Key 与托管适配器、页面请求状态。类型名可按现有风格调整，不为了复用改写整套 AI 搜索或认证。

现有 `DeepSeekIntentService`、`ManagedCookAIService` 只承担 SearchIntent；其严格校验及既有响应不变。复用能安全共享的凭据读取、网络策略、账号会话边界、鉴权和额度能力。阅读 `CookAISettings.swift` 与现有 coordinator 后接入当前模式选择，不再复制设置页或创建第二份 Key。

## 教学上下文和响应

建议上下文由 recipeID、公开数据版本/原文指纹、稳定 stepID、servings、requestID/context revision 组成。具体字段要与现有 `RecipeTextContent`、步骤 ID 和公开数据版本机制对齐，避免自行创建另一套步骤编号。

上下文构建仅允许公开且有效的文本菜谱；输入包含当前步骤和确有必要的原文片段、食材/工具以及已有计算器输出。每段有可追溯的 sourceID。请求问题及原文作为数据分隔，限制长度、字节数、tokens、请求时长和一次在途数量，不传历史或系统凭据。

定义小型版本化 JSON 响应，例如状态、简短讲解/本步答复、sourceIDs。拒绝未知字段/引用、错误版本、空回答、超限、来源漂移和不支持的数值。数值展示通过受验证的原文片段或确定性 UI 产生；模型文本不得成为新用量/时间/温度来源。校验无法确认的问题回到原文/「原方未说明」，不把模糊答案升级为可执行配方。不要承诺仅靠字符串/引用检查即可保证所有自然语言事实；在报告里说明实际约束与局限。

回答须匹配当前 context revision，更新状态前再次核对；成功和错误均检查取消、步骤及账号变化。内存回答离开上下文即清除，现有 `kitchen.workbench.v1` 等做菜数据仍由原逻辑恢复。

## 两种 AI 通道

个人 Key：沿用现有 Keychain、固定 DeepSeek HTTPS 端点、ephemeral session、不使用 cookies/cache/重定向及脱敏策略。新增教学调用，不把教学 prompt 塞进 SearchIntent.parse。模型选择沿用当前获准配置，不擅自更换平台模型。

会员托管：优先隔离新的教学 capability/契约与服务入口，例如独立 `cook-ai-guidance` 函数；若实际基础设施更适合版本化 action，需证明旧客户端与 #174 合约完全不受影响。旧 `ManagedCookAIService` 对 capability 顶层字段严格校验，不能直接给旧响应追加字段。教学须有独立可探测 readiness，不因搜索可用而启用。

托管端按 bearer token 验证身份和有效会员，复用现有总额度控制，调用前再核对必要状态；不接受客户端 userId/memberflag。服务端从打包的、可重建且带版本/指纹的公开文本菜谱快照解析 recipeID/stepID，与客户端不一致时拒绝并提示更新；不抓取任意 URL、不接受客户端伪造菜谱上下文。复用公开数据生成链，避免手写第二份菜谱。原始问题和完整回答不落库、不打日志。保留现有禁用/不可用回退，新增服务实现和本地测试不代表已经部署。

生产函数部署、真实付费调用和 TestFlight 不在本轮自动执行范围。PR 可以完整实现且默认安全关闭未就绪的托管通道，准确列出上线所需后续动作。

## 相关文件入口

- `apps/ios/Cook/Views/RecipeCookingComponents.swift` 及工作台入口
- `apps/ios/Cook/Model/KitchenWorkbench.swift`
- `apps/ios/Cook/Model/CookAISettings.swift`
- `apps/ios/Cook/Model/DeepSeekIntentService.swift`
- `apps/ios/Cook/Model/ManagedCookAIService.swift`
- `cloudfunctions/cook-ai-intent/index.js`（参考复用，保护原行为）
- `docs/dev/ios-kitchen-workbench.md`、`docs/dev/ios-kitchen-workbench-validation.md`
- `docs/dev/ios-managed-ai.md`、`docs/dev/ios-search-intent.md`
- `docs/decisions/0007-ai-and-sentence-input.md`（添加 #87 范围扩展记录）

## 精简验证

把纯契约/来源校验放在最小可测试模块，覆盖无原文、未知引用、版本/步骤漂移、越界数值和不支持的问题。应用侧重点验证迟到响应/错误、取消和账号切换，以及 AI 不改变备菜/份量/步骤完成/计时器；网络错误复用现有测试设施。托管端仅增加必要的契约、鉴权/会员/额度、服务端来源及旧接口兼容用例。

稳定候选在现有一台 iPhone 与一台 iPad 模拟器各做一次完整路径：打开有效菜谱 → 主动讲解 → 核对引用 → 本步提问 → 手动完成/继续 → 验证原有状态。已构建产物可复用；离线、空 Key、未部署托管入口可以用定向测试或同次 smoke 覆盖。检查大字体、VoiceOver 标签和焦点，实际真机朗读未做则明确保留待办。

确定性测试/模拟响应验证行为，不能冒称真实个人 Key 或生产托管验收。已有 CI 必需检查仍需通过；只跑与最终改动有关的本地检查，不新增昂贵矩阵、StoreKit/支付测试或重复全量回归。最终保留简短命令、结果和必要截图/日志路径，回收临时构建产物。
