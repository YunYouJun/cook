# 原生 Android 跨端协议与数据域规范

- 日期：2026-11-15
- 基线：`origin/main`（`41faba4aa`）
- 关联方案：[android-implementation-plan.md](./android-implementation-plan.md)

---

## 1. 各协议版本、尺寸限制与未知字段策略

| 数据 / 协议                                        | 版本字段与支持范围                                              | 体积 / 数量上限                                                             | 未知字段策略                                                           | 失败处理                                                    |
| -------------------------------------------------- | --------------------------------------------------------------- | --------------------------------------------------------------------------- | ---------------------------------------------------------------------- | ----------------------------------------------------------- |
| **内置公共菜谱包** (`recipe-meta.json` 等)         | `schemaVersion == 1`                                            | 按分片 `recipe-chunk-*.json` 或 `recipe.json`                               | 忽略已知模型外扩展属性                                                 | 版本不匹配抛出 `unsupportedSchemaVersion`，保留既有有效数据 |
| **饮食目标** (`recipe-dietary.json`)               | `schemaVersion` & `policyVersion` 支持 `1` 或 `2`（且二者相等） | 每目标 ≥ 10 条通过记录、全库 ≥ 30 条                                        | 忽略可选扩展                                                           | 校验菜谱/正文/用量指纹失效时仅关闭对应目标，不阻断主包      |
| **机构低 GI** (`recipe-institutional-low-gi.json`) | `schemaVersion == 1` 且 `policyVersion == 1`                    | 至少 10 条有效记录                                                          | 忽略可选扩展                                                           | 缺失、版本或来源/指纹不符时关闭该发现场景                   |
| **AI 智能找菜** (`SearchIntent`)                   | `schemaVersion == 1`, `policyVersion == 1`                      | `unresolved` 单条 ≤ 500 字符                                                | **严格拒绝未知键**（外层与 `query` 内层白名单）                        | 报错并保持现有筛选，提供本地解析入口                        |
| **AI 分步指导** (`StepGuidance`)                   | `schemaVersion == 1`, `policyVersion == 1`                      | 问题 ≤ 300 字符；回答 ≤ 1200 字符                                           | **严格拒绝未知键**                                                     | 不重试、不自动切换付费通道、保留原文步骤                    |
| **用户云同步文档** (`UserDataDocument`)            | 文档 `version == 1`；网关 `protocolVersion == 2`                | 编码后 ≤ 256 KiB；收藏 ≤ 5000、收藏墓碑 ≤ 1000、历史 ≤ 500、历史墓碑 ≤ 1000 | **允许**服务端或后续扩展附加字段（如 `updatedAt`）                     | 解码或校验失败拒绝覆盖本机数据                              |
| **私人菜谱导出** (`PrivateRecipeFile`)             | 便携文本 `version == 1`；原生视频关联 `version == 2`            | 文件 ≤ 5 MiB；总菜谱 ≤ 5000；单菜名 ≤ 30 UTF-16；note ≤ 20000；列表 ≤ 64 项 | **严格拒绝未知菜谱字段**                                               | 原子拒绝，原有菜谱与已有视频关联不受影响                    |
| **个人食材/厨具词典** (`PersonalCatalog`)          | `app == "cook"`, `kind == "personal-catalog"`, `version == 1`   | 文件 ≤ 256 KiB；自定义 ≤ 500；隐藏内置 ≤ 500                                | 校验必填字段                                                           | 原子拒绝，不破坏既有词典                                    |
| **云乐坊 SSO 回调** (`#ylf_sso=`)                  | `v3`（与 `@yunlefun/sso` 一致）                                 | 事务有效期 600 秒（10 分钟）                                                | 严格白名单字段校验                                                     | 回调 URI 精确匹配、校验 `issuer` 与 `nonce`、一次性消费     |
| **反馈与筛选信号** (`cook-feedback`)               | HTTP JSON body                                                  | Body ≤ 4096 B（`note` ≤ 200、`message` ≤ 500）                              | `PLATFORMS` 已支持 `'web'` / `'ios'` / `'android'`（部署云函数后生效） | 客户端始终发送 `'android'`，不伪装 `ios`/`web`              |

---

## 2. 本地数据域分区：`DeviceLibrary` vs `AccountLibrary(uid)`

Android 从 P2 建库阶段起即必须显式隔离本机匿名数据与账号数据，严禁后续在单表上简单加可空 `uid` 列混用：

1. **本机设备库 (`DeviceLibrary`)**
   - 存放未登录或未开启账号同步时的收藏（整数 `recipeId` + `time`）、收藏墓碑、浏览历史（`RecipeSnapshot` + `time`）、历史墓碑、`historyClearedAt`、冰箱库存与分类、私人菜谱（`customId`）与个人词典（`PersonalCatalog`）。
   - 设备默认饮食目标、主题、匿名信号开关保存在设备级 SharedPreferences（独立于账号）。
2. **账号同步库 (`AccountLibrary(uid)`)**
   - 按 `uid` 独立分区（或独立数据库/前缀表），仅当**云乐坊登录成功 + 具备有效会员 + 用户显式启用跨平台同步**时激活。
   - **首次启用同步**：创建独立 `AccountLibrary(uid)` 视图并与云端文档同步，**严禁自动把 `DeviceLibrary` 里的旧记录上传给当前账号**。
   - **显式迁入**：「将本机记录复制到当前账号」作为用户主动触发的显式操作，将 `DeviceLibrary` 收藏/历史合并进 `AccountLibrary(uid)` 并触发同步。
   - **关闭同步 / 退出登录**：立即切回 `DeviceLibrary`，**保留 `AccountLibrary(uid)` 的本地离线缓存**，同一账号再次开启同步时可恢复本地未上传队列。
   - **账号切换与竞态保护**：每次账号、同步开关或身份状态变更递增单调 `sessionRevision`；网络请求返回时若 `sessionRevision` 已变或同步已关闭，必须丢弃响应，绝不覆盖新状态。

---

## 3. `UserDataDocument` 合并、裁剪与二次重合并规则

1. **身份与时间戳**：
   - 所有时间戳均为 Unix 毫秒（非负数，有限浮点数）。
   - 收藏以公共菜谱整数 `id` 为主键；同一 `id` 在添加与删除时间戳相同时，**删除获胜**（`adds.time > removes.time` 才保留）。
   - 历史以 `(recipe.name, time)` 为身份键，保存完整 `RecipeSnapshot`（`name`, `stuff`, `tools`, `link`, `bv`, `difficulty`, `emojis`, `tags`, `methods`）；任何 `time <= max(historyClearedAt)` 的历史及历史墓碑均被清零过滤。
2. **确定性裁剪**：
   - 合并后历史按 `(time, name)` 升序保留最新 **200** 条。
   - 收藏墓碑与历史墓碑过滤掉超过 **180 天** 的记录，按时间倒序（同时间按 `id` 或 UTF-16 字典序稳定破平）保留最多 **500** 条。
3. **传输与二次合并**：
   - 请求头携带 Bearer Token，负载绑定 `ownerDigest = SHA256(subject)`。
   - 同步流程：`读取本地快照 S0 -> 上传/拉取云端返回合并文档 R -> 读取当前最新本地状态 S1 -> 计算 S1 与 R 的二次本地合并 M（吸收请求期间产生的本地新增/删除）-> 原子写入数据库`。

---

## 4. 文件互通 vs 云同步边界

- **文件导入（SAF JSON）**：定位为**跨端离线迁移/补充**，默认采用非破坏性合并（新增/按 ID 去重；私人菜谱同 `customId` 不同内容提示跳过或另存带 `copiedFrom` 副本；v1 文本导入绝不清除本机已关联的 v2 视频），**不直接复用带有远端墓碑淘汰语义的云同步逻辑**。
- **私人菜谱视频边界**：v2 原生导出仅含视频关联元数据，不含视频字节流；Android 不解析来自 iOS 的 iCloud Drive 路径/引用。

---

## 5. 凭据存储与后台调度状态机

1. **Tink + Keystore Vault**：使用 `tink-android` AEAD（Keystore 主密钥包裹数据密钥），加密保存 SSO Token 与 DeepSeek BYOK API Key；关闭 Android Auto Backup 与设备迁移（`allowBackup` / `data_extraction_rules.xml`）。密钥损坏或不可用时报错并保留菜谱库，严禁回退明文。
2. **后台提醒分离**：
   - **冰箱每日临期提醒**：通过 `AlarmManager` 发起非精确周期检查，以本地日历日期为键持久化去重，每天最多发 1 条（明确说明受 Doze 影响可能延迟）。
   - **厨房工作台倒计时**：本地工作台文件持久化截止时间 `deadlineEpochMs`；优先通过 `AlarmManager` 调度，检查 `canScheduleExactAlarms()`，无精确闹钟权限时明确提示用户可能延迟或引导授权；处理设备重启（`BOOT_COMPLETED`）、系统时间/时区/DST 变更与权限撤回后的重排。
