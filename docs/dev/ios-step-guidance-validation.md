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

- 未部署 `cook-ai-guidance`，未配置生产 `COOK_AI_GUIDANCE_ENABLED` / 模型能力，也未进行真实会员 HTTP 调用。
- 未使用个人 DeepSeek Key 发起真实付费请求。
- 未上传 TestFlight，未在真机执行 VoiceOver 朗读、焦点顺序或断网恢复。
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
