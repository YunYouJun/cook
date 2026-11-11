# 步骤讲解验证记录

2026-11-09，Xcode 27.1 / Swift 6.4。功能与数据边界见 [iOS 步骤讲解与提问](./ios-step-guidance.md)。

## 自动化范围

| 范围                                                            | 结果                               | 覆盖                                                                                           |
| --------------------------------------------------------------- | ---------------------------------- | ---------------------------------------------------------------------------------------------- |
| CookCore `StepGuidanceTests`                                    | 7 通过，0 失败                     | 请求绑定、来源白名单、当前步骤必引、空/超长回答、数字注入、来源与版本漂移                      |
| App `StepGuidanceServiceTests`、`KitchenStepGuidanceModelTests` | 9 通过，0 失败                     | 固定端点、个人 Key 请求体、托管能力隔离、响应校验、取消、迟到响应和上下文失效                  |
| Node 托管服务与来源生成                                         | 49 通过，0 失败                    | 独立开关、鉴权/会员/额度、来源漂移、未知引用、数字注入、模型请求不含客户端正文、旧 intent 兼容 |
| 工作台 UI 流程                                                  | iPhone 2 通过；iPad 1 通过，0 失败 | 主动讲解、引用核对、本步提问、手动完成，以及备料、份数、进度、暂停计时保持不变                 |

上述开发 UI 断言使用 `-ui-testing-step-guidance` 注入本地确定性 transport，不发起真实 DeepSeek、账号或 CloudBase 请求。

来源快照校验版本为 `353bc8dab0ea`，共 232 道菜、2257 个步骤。

## 模拟器验收

共享设备按现有清单复用，串行执行并显式关闭并行测试，不克隆、清空或新建同款模拟器。

| 设备                  | 系统     | 流程                       | 结果            |
| --------------------- | -------- | -------------------------- | --------------- |
| iPhone 18 Pro         | iOS 27.0 | 标准字号浅色完整教学闭环   | 通过，94.446 秒 |
| iPad Pro 11-inch (M5) | iOS 27.0 | 宽屏完整教学闭环           | 通过，98.637 秒 |
| iPhone 18 Pro         | iOS 27.0 | 最大辅助字号深色布局与讲解 | 通过，54.692 秒 |

iPhone 最终 UI 测试在按钮改为辅助字号纵向排列后重新执行：

- 标准闭环：`/tmp/cook-ai-step-guidance-final-0423.xcresult`
- 最大辅助字号深色：`/tmp/cook-ai-step-guidance-accessibility-0427.xcresult`

- 最大辅助字号下「本步讲解」「问这一步」改为纵向全宽按钮，测试断言两者不共处同一行，并滚动到回答主体后截图。
- 测试覆盖控件至少 44pt、深色模式、长回答滚动及引用展开；最终截图确认按钮不再逐字换行。固定进度栏和计时栏仍会覆盖部分滚动内容，极端字号下长回答无法与两栏同时完整同屏；自动化已验证回答可滚动到计时栏上方并继续触达。
- 自动化标签、触达区和截图人工检查不等于 VoiceOver 真人听读、焦点顺序或断网恢复验收。

## 未执行

- 未使用个人 DeepSeek Key 发起真实付费请求。
- 未在真机执行 VoiceOver 朗读、焦点顺序、断网恢复或极端字号人工验收，未更新维护者手机。
- 未验证生产模型对任意问题的事实正确性；当前检查只包括契约、引用来源和可机械识别的数值约束。

## 复现

```bash
node scripts/generate-ai-intent-contract.mjs --check
node scripts/generate-ai-guidance-sources.mjs --check
node --test test/cookAiIntent.test.mjs test/aiIntentContract.test.mjs \
  test/cookAiGuidance.test.mjs test/aiGuidanceSources.test.mjs

xcodebuild -project apps/ios/Cook.xcodeproj -scheme Cook \
  -destination 'platform=iOS Simulator,id=<shared-device-udid>' \
  -derivedDataPath /tmp/cook-ai-step-guidance-derived \
  CODE_SIGNING_ALLOWED=NO -parallel-testing-enabled NO \
  -collect-test-diagnostics never test \
  -only-testing:CookUITests/KitchenWorkbenchUITests/testStepGuidanceUsesCurrentSourceAndLeavesManualCookingStateUnchanged
```

上述 `/tmp` xcresult 是清理前的人工核对路径。任务结束后回收 `/tmp/cook-ai-step-guidance-derived` 等本轮可重建产物；源码、来源生成输入和本报告保留。

## 2026-11-11 数值校验修复

同步最新 `main`，解决隐私政策更新日期冲突，保留步骤讲解和冰箱照片识别两边的隐私说明。

- 修复前先运行新增回归测试，确认「4ml 食用油 → 加热 4 分钟」会错误通过。修复后，Swift 与 Node 均从每条引用独立提取完整数值表达式，再精确匹配；不再以数字子串判断来源支持。
- 两端共用 `packages/data/fixtures/step-guidance-numbers.json` 的 57 个案例，覆盖单位置换、整数/小数子串、中文百千、大小勺、约数、复合时间单位、分数/带分数、符号/范围、Unicode 数字及跨引用拼接；同时保留原文数值和合法空白、单位大小写的正例。
- Node 定向检查 50 通过，0 失败；CookCore 全量测试通过，包含运行上述共享案例的 `StepGuidanceTests`。来源快照与旧 intent 契约生成检查均通过；`pnpm lint --fix` 通过。
- 规范与规格增量复核均通过。语法检查仍不证明全部自然语言事实，不将这些案例的通过描述为任意数值表达或生产模型已验收。
- 本轮 Swift 构建使用任务独占临时目录并在退出时回收，两次构建共回收约 287 MiB；未新建或运行模拟器。未重复执行 UI、真实模型调用、生产部署、TestFlight 或真机无障碍验收。

本轮命令：

```bash
node scripts/generate-ai-intent-contract.mjs --check
node scripts/generate-ai-guidance-sources.mjs --check
node --test test/cookAiIntent.test.mjs test/aiIntentContract.test.mjs \
  test/cookAiGuidance.test.mjs test/aiGuidanceSources.test.mjs
swift test --package-path apps/ios/CookCore --scratch-path <task-temp>
pnpm lint --fix
```

## 2026-11-11 发布候选准备

从独立工作树冻结 `674d3b5f`，构建 `2.0.0 (15)`。归档、仅内部导出、共享签名／隐私清单检查及 Apple `altool --validate-app` 均通过。最终 IPA 中三个反馈／AI 地址与正式配置一致，12 个数据文件逐字节匹配构建输入，数据版本为 `353bc8dab0ea`，隐私正文与源码一致，App 与 dSYM UUID 匹配。IPA SHA-256 为 `57a9b7a0bda547dbef5fd0eaf92f78aad6945ee64231dcd31cc8ef3b2e42ed64`。

函数 ZIP 已预打包固定版本的 `@cloudbase/node-sdk@3.18.3`，四个源文件与候选源码逐字节一致，模块加载及 GET 405 的本地检查通过，不含生产环境变量。准备阶段只读核对时生产新函数尚不存在，公开路径返回 404；后续关闭状态部署见下方记录。

构建和展开函数的中间目录分别回收 256,588,213 和 16,395,511 字节，合计约 260.3 MiB；归档、dSYM、IPA、函数 ZIP、必要源码输入和回执保存在仓库外。未创建、启动或清空模拟器，未修改其他任务工作树。详细状态见[准备证据](./evidence/step-guidance-release-preparation-2026-11-11.json)与[发布收尾](https://github.com/YunYouJun/cook/blob/main/specs/ai-step-guidance/release-closeout.md)。

## 2026-11-11 生产关闭状态部署

在上述准备之后，新函数已部署为 `Active`，回读 `Nodejs24.11`／256 MiB／45 秒／关闭云端依赖安装，四个源文件与候选逐字节一致。复用搜索的生产通道、配额摘要密钥与 10／3／100 限额，新函数独立开关保持关闭；搜索配置及修改时间未变。

新增精确 HTTP 路径，既有根路径与 `/fc-api` 保持原值。未登录／无效令牌分别为 `401 LOGIN_REQUIRED`，GET 为 `405 METHOD_NOT_ALLOWED`。复用已有受管生产会员号，教学 capability 为 HTTP 200、会员有效且 `enabled=false`；搜索 capability 仍为 HTTP 200、`enabled=true`。本轮短期会话已注销，没有模型调用或配额预留。

该阶段资源包查询发现有效 `pkg_tcb_credits_10000`，与本机 AI 接入技能的旧 Token 包前缀规则不同，因此先保留关闭状态；未据此认定环境无额度，也未购买新包或切换通道。关闭状态的历史快照见[部署证据](./evidence/step-guidance-disabled-deployment-2026-11-11.json)，后续实际验收见下方。

本轮文档新增的三处 `specs/` 站外相对链接触发 CI 的 VitePress 死链检查，已修为仓库文件链接；修正后本地完整文档构建通过，约 5.9 MiB 输出随任务临时目录回收。独立发布工作树也已移除，回收约 15.1 MiB；必要归档输入和报告已保留。

## 2026-11-11 真实会员讲解与最终启用

依据[官方计费说明](https://docs.cloudbase.net/ai/FAQ)及[资源点迁移公告](https://www.cloudbase.net/blog/2026/06/26/resource-point-token)，核实资源点可抵扣模型 Token。生产标准版为 `credits` 模式、`NORMAL`、用量状态正常、费用状态 `positive`，现用模型已启用；旧 Token 包前缀规则不适用于该资源点路径。没有购买新包、更换 provider 或模型。

只调整新教学函数的独立开关，复用既有受管会员的正常登录流程，完成一次真实 HTTP 讲解。返回 200、状态 `answer`；菜谱、数据版本、原文指纹、稳定步骤 ID、请求身份及上下文 revision 均匹配。142 字回答包含 3 个合法引用，当前步骤必引和完整数值校验通过；没有自动 HTTP 重试。接口不暴露 Token 用量，因此未记录或推测具体计费 Token 数，也不把单次契约通过扩展为任意自然语言事实验收。

验收后先恢复关闭；内部构建就绪后最终启用教学 capability，并再次回读真实会员 capability 与既有搜索 capability，均为启用状态。既有搜索函数配置及修改时间未变，原配额与摘要密钥保留；最终启用没有再次请求模型。两次临时会员会话均已注销。详见[真实验收证据](./evidence/step-guidance-live-acceptance-2026-11-11.json)。

## 2026-11-11 构建 15 内部分发

上传前重新查询 Apple 构建和上传记录，确认原构建 15 会话只有分析文件、没有 App 二进制。同一 SHA-256 的 IPA 只上传一次，实际构建 ID 为 `384e4392-8703-4b68-a21b-0eaa54c6d1dc`。共享 `ready`／`published` 门禁通过，回读 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`、外部 `NOT_APPLICABLE`、未过期、无外部组。

既有内部组自动关联，沿用 1 名成员，未重复写入组关联；中文测试说明已写入并回读一致，保留个人 Key 与真机人工项目未验证的说明。首次 `published` 复核的只读 ASC 查询失败，核对同一 App 与组后完整复查通过，没有重传 IPA。内部候选记录仅在分发确认后更新，外测构建 7 及其邀请保持原值。详见[分发证据](./evidence/step-guidance-build15-published-2026-11-11.json)。
