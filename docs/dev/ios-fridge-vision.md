# iOS 冰箱 AI 识图

冰箱工具栏的「拍照识别」是统一入口。拍照或选图后，先在设备上用 Apple Vision 识别包装、日期和常见蔬果。识别到日期线索或一种食材时，直接进入单份核对。结果不确定时，可选择批量识图或按一份食材录入。两种操作复用选中的照片。单份核对也可主动改用批量识图。

照片读取、联网识别失败或未识别出食材时，界面提供重试或换图。换图回到统一的拍照和选图页。

选择批量识图后，App 核对云乐坊会员资格。未登录时可登录，非会员可查看会员权益，也可回到同一张照片的本机识别结果。通过核验后，App 在本机旋转、缩小并重新编码为 JPEG，去除位置等元数据。预览页在底部的「同意并识别」上方显示照片接收方。用户点按后才会上传。结果页可改名和取消勾选，底部的「加入冰箱」显示将要保存的数量。每种食材记一份，继续使用建议日期、自动分类及现有同步。

iOS 和 Android 在验证改名结果前会进行 NFC 规范化，再按 Unicode 码点计数，最多 30 个。名称须含字母或数字，也可包含空格、间隔点、括号和连字符。服务端与两端共用 `packages/data/fixtures/fridge-vision-names.json` 中的验证样例，涵盖生僻字、可组合重音、键帽表情和只含标点的输入。

服务端可选择腾讯云 CloudBase 托管的 DeepSeek、DeepSeek 官方 API 或阿里云百炼。App 用一次带登录令牌的 `GET` 同时核对会员资格和当前接收方，查询阶段照片留在本机。确认页展示接收方，上传与用户同意的提供方绑定。如果切换发生在预览和上传之间，云函数不转发照片、不计入额度，App 更新接收方后需要用户再次确认。模型故障时保留当前提供方供重试。

初次核对或上传收到 HTTP 401 时，App 会在同一会话续期一次。上传遇到 401 后，App 保留照片，重新核对会员资格和接收方，等待用户再次点「同意并识别」。核对仍被拒绝时可前往账号设置重新登录。退出或切换账号会结束旧会话的上传和结果核对。

每次 `POST` 都通过云乐坊账号服务重新核验会员有效期，再预留额度并转发照片。非会员或查验失败时停止识别。未携带令牌的 `GET` 只返回接收方，旧客户端也须通过 `POST` 的会员校验。

## 开启服务

1. 在现有 `yunlefun-8g7ybcxc7345c490` 环境新建独立 Event Function `cook-fridge-vision`，为 `https://api.yunle.fun/cook-fridge-vision` 设置 HTTP `GET`（查询接收方）与 `POST`（识别）访问。函数源为 `cloudfunctions/cook-fridge-vision`，使用 Node.js 24.11。在独立目录预装生产依赖并随部署包上传，关闭云端依赖安装。生产网关实测可通过约 96 KiB 的 JSON 请求，约 128 KiB 返回 413；客户端与函数均将 JPEG 限制为 70 KiB，使编码后请求小于 94 KiB。访问日志、追踪和错误采集不记录请求体或授权头。
2. 创建 `cook_fridge_vision_quota` 集合，仅允许管理端／函数访问，为 `expiresAt` 建索引。每次原子预留全局和账号每日额度，按中国标准时间换日；服务按批回收过期计数。不复用文字 AI 的额度或数据库记录。
3. 设置 `COOK_FRIDGE_VISION_PROVIDER`。CloudBase 托管通道使用同一环境的函数身份和资源点；直连通道注入对应的服务端 Key。另注入 `COOK_FRIDGE_VISION_QUOTA_SALT`（至少 32 字符的独立随机值），设置 `COOK_FRIDGE_VISION_ENABLED=true`。`COOK_FRIDGE_VISION_USER_DAILY_LIMIT` 和 `COOK_FRIDGE_VISION_DAILY_BUDGET` 默认每账号每日 8 次、全站每日 150 次。配置不完整时服务关闭。账号令牌仅用于向 CloudBase 核验身份，模型只收到经处理的图像和固定提示词。
4. 用有权使用的非私密冰箱测试图验证真实模型、无效会话和网关体积。原生构建设置 `COOK_FRIDGE_VISION_SERVICE_URL` 默认指向 `https://api.yunle.fun/cook-fridge-vision`；置空时「拍照识别」仅提供本机流程。

| `COOK_FRIDGE_VISION_PROVIDER` | 模型                                                                                          | 服务端 Key                            |
| ----------------------------- | --------------------------------------------------------------------------------------------- | ------------------------------------- |
| `qwen`（默认）                | [阿里云百炼](https://help.aliyun.com/zh/model-studio/vision) `qwen-vl-plus`                   | `COOK_FRIDGE_VISION_API_KEY`          |
| `deepseek`                    | [DeepSeek 官方视觉 API](https://api-docs.deepseek.com/zh-cn/guides/vision) `deepseek-flash`   | `COOK_FRIDGE_VISION_DEEPSEEK_API_KEY` |
| `cloudbase`                   | [CloudBase 多模态](https://docs.cloudbase.net/ai/model/multimodal) `deepseek-v4.1-flash-1017` | 复用云函数身份和环境资源点            |

冰箱识图使用云乐坊会员服务及独立的服务端配置。手机上保存的个人 DeepSeek Key 用于智能找菜和步骤讲解。三个通道共享识图额度。切换至 `cloudbase` 前，使用支持此接收方的 iOS 构建验收；仅认识 `deepseek`、`qwen` 的旧构建会拒绝这个接收方。在生产切换前，核对 App 的上传告知、隐私政策、模型授权材料和仍在分发的测试构建。

本地可运行 `node --test test/cookFridgeVision.test.mjs` 验证 HTTP 契约和额度；iOS `FridgeVisionTests` 验证图像处理及上传契约，`FridgeVisionUITests` 使用内存测试图和替代响应验证同意与批量入库，不调用线上模型。

## 生产验收 · 2026-11-17

`cook-fridge-vision` 已在 CloudBase 上使用 Node.js 24.11，接收方查询返回 `deepseek`，未登录的识图请求返回 401。函数只添加到 `api.yunle.fun` 的对应路径，原有路由保持不变。首次使用完整测试照时网关返回 413；调整压缩后，实际生产请求返回 200，识别出鸡蛋、番茄、胡萝卜、西兰花和黄瓜。测试身份的会话已退出。

iPhone 18 Pro / iOS 27.0 模拟器使用同一张图和这次的真实候选，在确认页取消三样，点击「加入冰箱（2 样）」后只看到鸡蛋、番茄。5 项 iOS 单元测试和这条界面流程通过。本轮生产请求和模拟器勾选分段验证，没有在模拟器中验证相机拍摄或原生 SSO，也没有分发新构建。

[测试照片](./evidence/fridge-vision-deepseek-2026-11-17.jpg) · [验收记录](./evidence/fridge-vision-deepseek-2026-11-17.json)

同日后续已上线会员门禁。用生产受管测试账号复测：非会员的资格查询返回 `member: false`，上传返回 `403 MEMBERSHIP_REQUIRED`；有效会员的资格查询返回 `member: true`，同一张照片仍识别出五种食材。两次会话均已退出。模拟器验证了非会员查看权益后可直接进入离线拍照，以及会员的勾选入库流程。
