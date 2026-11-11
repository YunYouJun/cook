# iOS 步骤讲解与提问

关联 [#87](https://github.com/YunYouJun/cook/issues/87)。该能力只解释厨房工作台当前显示的公开文本步骤，帮助用户理解后自行完成；不生成新菜谱、不替用户勾选，也不改变备料、份数、步骤进度或计时器。

## 入口与交互

- 当前未完成步骤的原文卡片下方提供「本步讲解」「问这一步」，只有用户主动点按时才发起请求。
- 回答与原文引用在步骤卡片外展开。引用可展开核对，且必须包含当前步骤。切换菜谱、步骤、份数、原文、AI 模式或账号后，旧请求取消、旧回答清除；迟到的成功或错误不能覆盖新状态。
- 单次只在内存中保留一个回答，不建立长对话、聊天 Tab 或后台记忆。关闭工作台后只恢复原有 `kitchen.workbench.v1` 做菜状态。
- 操作控件使用原生语义和至少 44pt 触达区；AI 控件不在整张步骤完成按钮内，不会因讲解误勾选。

## 教学契约

`CookCore` 的 `StepGuidanceRequest` 将请求绑定到 `recipeID`、`dataVersion`、`sourceFingerprint`、`stepID`、`servings`、`kind`、`requestID` 与 `contextRevision`。`stepID` 沿用 `RecipePresentation` 的“同名出现次数 + 原文”，不是步骤序号。

模型草稿只接受 `status`、`answer`、`sourceIDs`：

- `status` 仅为 `answer` 或 `source-does-not-say`。
- 回答为 1–1200 字，引用为 1–4 个不重复的已提供来源，并且必须包含当前步骤。
- 回答中的数值表达式必须与某条引用中的完整表达式一致，连同单位、正负号、范围、分数和常见中文约数一起核对；不接受数字子串、单位替换或跨引用拼接。空白与拉丁单位大小写可以不同，不自动换算单位或取范围端点。
- Swift 与托管端运行同一份数值测试语料，覆盖中文百千、大小勺、带分数、复合时间单位等常见形式；未知字段、未知引用、版本漂移、超限与空回答拒绝显示。
- 语法和引用检查不能证明所有自然语言事实。界面明确提示引用只用于定位原文，用量、时间、温度、熟度与进度仍以原文和工作台为准。

来源 ID 沿用客户端展示顺序：`recipe:name`、`step:<stepID>`、相邻步骤和 `ingredient:<序号>`。托管服务端从打包的公开菜谱快照重建同一组来源，不信任客户端上传的原文或任意 URL。

## 两种 AI 通道

个人 Key 路径沿用现有设置、Keychain 和固定 DeepSeek 地址。请求使用无 Cookie、无缓存、禁止重定向的临时网络会话，只发送当前菜谱名、当前及前后步骤、前 24 项原料和用户问题。Key 不上传云乐坊、不写入普通设置、导出文件或日志。

会员路径使用独立 `cook-ai-guidance` 函数和独立 `COOK_AI_GUIDANCE_ENABLED` 开关，不复用搜索能力的 readiness。客户端先探测自己的 capability，再发送公开菜谱 ID、版本、原文指纹、步骤 ID、份数、请求身份和问题；不发送原文正文、userId、会员标志或个人 Key。服务端重新核验非匿名账号、有效会员与到期时间，并与搜索服务共用 `cook_ai_quota` 的账号摘要、分钟限制、日限制和全局预算。

搜索可用不能推出教学可用；旧 `cook-ai-intent` 的 `SearchIntent` 请求、响应和兼容契约保持不变。

## 来源快照

`scripts/generate-ai-guidance-sources.mjs` 从 `recipe-text.json`、`recipe-amounts.json` 和当前公开菜谱目录生成 `cloudfunctions/cook-ai-guidance/sources.json`。生成器复刻 `RecipePresentation` 的章节、同名出现次数、补充步骤前缀和步骤 ID 规则，只收录至少有一个有效文本步骤的公开菜谱；源文件变化会改变整菜指纹并使旧请求得到来源漂移错误。

更新公开菜谱正文后执行：

```bash
pnpm convert
node scripts/generate-ai-guidance-sources.mjs
node scripts/generate-ai-guidance-sources.mjs --check
node --test test/cookAiGuidance.test.mjs test/aiGuidanceSources.test.mjs
```

来源解析、引用和数值检查只能约束可机械核对的结构；不能保证模型理解所有自然语言，也不能替代菜谱内容审查。

## 失败与发布边界

无原文、无 Key、未登录、非会员、服务未部署、版本或来源不符、离线、超时、额度不足和非法响应都显示简短状态，原文与其他做菜功能继续可用。重试必须由用户再次点按，不自动重试或切换计费通道。

2026-11-11 已完成新函数关闭状态部署，按官方计费说明及生产 `credits` 模式核实资源点适用范围，并以既有会员完成一次真实 HTTP 讲解。请求绑定、引用与完整数值校验通过，最终教学 capability 已启用；原搜索配置、共享配额和摘要密钥保持原值，没有购买资源包或切换模型。单次请求不代表生产模型对任意自然语言事实已验收，接口也不暴露具体 Token 用量。

同一份通过签名／隐私清单和 Apple 包校验的 `2.0.0 (15)` IPA 已完成既有内部组分发，回读 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，中文测试说明一致。个人 Key 实调、真机 VoiceOver／焦点顺序、断网恢复与极端字号人工验收仍未执行，本轮未更新维护者手机。精确状态与回退顺序见[发布收尾](https://github.com/YunYouJun/cook/blob/main/specs/ai-step-guidance/release-closeout.md)、[真实请求证据](./evidence/step-guidance-live-acceptance-2026-11-11.json)和[分发证据](./evidence/step-guidance-build15-published-2026-11-11.json)。

验证范围与复现命令见[验证记录](./ios-step-guidance-validation.md)。相关边界也记录在 [0007 AI 与整句识别](../decisions/0007-ai-and-sentence-input) 和 [iOS 隐私政策](../privacy)。
