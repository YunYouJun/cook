# Cook Pro & 海外免登录云端 AI 上线检查清单（3–5 天可执行版）

> 本文档对应分支 `feat/cook-pro-overseas-ai` 的双商品分层方案：**全区无登录永久买断（Cook Pro）** + **非中国大陆地区免登录云端 AI 自动续期订阅**。

---

## 1. 商品架构与边界核对

| SKU                           | 类型                       | 开放地区                            | 是否需要云乐坊账号            | 核心权益                                                                                                              |
| ----------------------------- | -------------------------- | ----------------------------------- | ----------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `fun.yunle.cook.pro_lifetime` | Non-Consumable（永久买断） | 全部地区（含 `CHN`）                | **不需要**（纯 StoreKit 2）   | 无限冰箱食材（免费 12 样）、无限私房菜谱（免费 3 道）、全部 3D 菜品展示（免费第 1 道）；海外支持填写个人 DeepSeek Key |
| `fun.yunle.cook.ai_month`     | Auto-Renewable（月订阅）   | 仅非中国大陆（`storefront != CHN`） | **不需要**（`AppleJWS` 验签） | 包含全部 Cook Pro 权益 + 3 项云端 AI（智能找菜、冰箱批量识图、步骤原文讲解）                                          |
| `fun.yunle.cook.ai_year`      | Auto-Renewable（年订阅）   | 仅非中国大陆（`storefront != CHN`） | **不需要**（`AppleJWS` 验签） | 同 `ai_month`（同一 Subscription Group，推荐档）                                                                      |

**数据保护与合规铁律：**

- 免费上限仅阻止**新增超出部分**，已有食材和私房菜谱永不删除、不锁定、仍可编辑/导出/消耗。
- 常规 Release 构建关闭 Cook Pro 和海外 AI。完成商品、云函数及真机 Sandbox 验收后，分发构建显式设置 `COOK_PRO_STORE_ENABLED=YES` 和 `COOK_OVERSEAS_AI_ENABLED=YES`，继续保持 `COOK_ONLINE_AI_ENABLED=NO`。中国大陆或 Storefront 未解析时，隐藏云端 AI 订阅入口与「AI 设置」。
- `fun.yunle.cook.pro_lifetime` **严禁**出现在后端允许调用云端 AI 的产品白名单中。

---

## 2. App Store Connect 配置清单

1. **创建 3 个 App 内购买项目（Bundle ID: `fun.yunle.cook`）：**
   - 非消耗型：`fun.yunle.cook.pro_lifetime`（建议中国区 ¥28 / 美区 $3.99，勾选家庭共享可按需配置）。
   - 创建订阅群组：**`Cook Cloud AI`**（参考本地化显示名：`云食册海外云端 AI` / `Cook Cloud AI`）。
   - 自动续期订阅 1：`fun.yunle.cook.ai_month`（1 个月，建议参考定价 $2.99 / 对应港区台区日区等价档）。
   - 自动续期订阅 2：`fun.yunle.cook.ai_year`（1 年，建议参考定价 $19.99）。
2. **定价与地区可用性建议：**
   - `pro_lifetime` 面向全部 App Store 国家/地区上架。
   - `ai_month` / `ai_year` **不要在中国大陆地区上线销售**（或至少与客户端 Storefront 策略一致，仅开放非中国大陆国家/地区购买），避免审核误触国内大模型备案要求。
3. **补全付费与订阅审核信息（Guideline 3.1.2）：**
   - 每个内购项目上传至少 1 张 `CookProPaywallView` 截图与简短审核备注。
   - 在 App Store Connect 的 App Description 中附上隐私政策 URL 与 Apple 标准 EULA（或自定义使用条款）链接；`CookProPaywallView` 内已包含「恢复购买」「管理订阅」「隐私政策」「使用条款（EULA）」。
4. **记录 App Apple ID：**
   - 在 App Store Connect -> 综合 -> App 信息 中复制纯数字 **Apple ID**（例如 `674xxxxxxx`），用于 CloudBase 环境变量 `COOK_APPLE_APP_ID`。

---

## 3. CloudBase 云函数部署前准备

三个云函数共用同一套 Apple IAP 验签逻辑（唯一源头：`cloudfunctions/_shared/apple-iap-auth.js` 与 `cloudfunctions/_shared/certs/*.cer`）：

1. **同步共享文件并校验：**
   ```bash
   node scripts/sync-apple-iap-auth.mjs
   node scripts/sync-apple-iap-auth.mjs --check
   ```
2. **安装云函数依赖并打包：**
   `cook-ai-intent`、`cook-fridge-vision`、`cook-ai-guidance` 的 `package.json` 均已新增 `"@apple/app-store-server-library": "^3.1.0"`。部署前需在各云函数目录运行 `npm install --omit=dev` 或通过 CloudBase 云端安装依赖。
3. **为三个云函数配置环境变量（严格校验以下键值）：**

   | 变量名                         | 推荐值 / 说明                                                                                      | 是否必填                               |
   | ------------------------------ | -------------------------------------------------------------------------------------------------- | -------------------------------------- |
   | `COOK_APPLE_IAP_ENABLED`       | `true`                                                                                             | 必填（开启 `AppleJWS` 鉴权）           |
   | `COOK_APPLE_APP_ID`            | App Store Connect 纯数字 Apple ID（如 `674xxxxxxx`）                                               | 生产环境**必填**（缺省会 fail-closed） |
   | `COOK_APPLE_IAP_ALLOW_SANDBOX` | **`true`**（上线初期及 **App Store 审核期必须开启**，因 Apple 审核与 TestFlight 使用 Sandbox JWS） | 推荐开启                               |
   | `COOK_APPLE_BUNDLE_ID`         | `fun.yunle.cook`                                                                                   | 可选（默认值）                         |
   | `COOK_APPLE_AI_PRODUCT_IDS`    | `fun.yunle.cook.ai_month,fun.yunle.cook.ai_year`（禁止包含 `pro_lifetime`）                        | 可选（默认值）                         |
   | `COOK_APPLE_IAP_ISSUER_ID`     | App Store Connect 的 In-App Purchase 密钥签发者 ID                                                 | 必填                                   |
   | `COOK_APPLE_IAP_KEY_ID`        | 对应 In-App Purchase 密钥 ID                                                                       | 必填                                   |
   | `COOK_APPLE_IAP_PRIVATE_KEY`   | 对应 .p8 私钥，保存在云函数环境变量中                                                              | 必填                                   |

   每次核验都会根据 Apple 签名交易的原始交易编号查询当前订阅状态。退款、停止有效或状态不可核验时不调用模型。中国大陆或缺失 Storefront 的签名交易会被拒绝。

4. **运行云函数测试：**
   ```bash
   node --test test/appleIapAuth.test.mjs test/cookAiIntent.test.mjs test/cookFridgeVision.test.mjs test/cookAiGuidance.test.mjs
   ```

---

## 4. iOS 端真机 / TestFlight 验收清单

1. **本地 Xcode StoreKit 测试：**
   - 运行 `CookProStoreKit` scheme，验证本地商品类型、订阅分组及永久买断在重新读取权益后仍生效。用 `apps/ios/StoreKitValidation/Fixtures/CookProProducts.storekit` 在支付页继续验证海外月/年订阅和「恢复购买」。
   - 切换 StoreKit Configuration `_storefront` 为 `CHN` vs `USA` 验证中国大陆隐藏云端 AI 与订阅入口。
2. **TestFlight Sandbox 真机验证（需真实 Apple Sandbox 账号）：**
   - **中国大陆区 Sandbox 账号**：进入「我的 -> Cook Pro 永久买断」，确认**只展示一次性买断**；「AI 设置」完全隐藏；完成买断后冰箱可存超过 12 样、私房菜谱可新建超过 3 道、所有 3D 展示可打开。
   - **海外区 Sandbox 账号（如美区/港区/日区）**：不登录云乐坊账号，直接订阅 `ai_month` 或 `ai_year`，确认后端云函数收到 `Authorization: AppleJWS <jws>`（Sandbox 环境验签通过），智能找菜、冰箱批量识图、步骤讲解均可直接使用。
   - **恢复购买与重装**：卸载重装后点「恢复购买」，确认 Pro 买断与 AI 订阅均自动恢复。

---

## 5. App Store 审核备注模板（App Review Notes）

可直接复制并在提交审核时按实际测试情况微调：

> 1. All purchases ("Cook Pro" one-time Non-Consumable `fun.yunle.cook.pro_lifetime`, and Auto-Renewable Subscriptions `fun.yunle.cook.ai_month` / `fun.yunle.cook.ai_year` outside Mainland China) use native StoreKit 2 and do **not** require registering or signing in to any third-party account.
> 2. Cloud AI subscriptions (`ai_month` / `ai_year`) and generative AI settings are strictly disabled in Mainland China storefronts (`CHN`) and enabled only for non-Mainland App Store storefronts.
> 3. Existing user pantry items and private recipes saved before reaching the free limit remain fully readable, editable, and exportable.
