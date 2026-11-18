# iOS 冰箱 AI 识图

冰箱页面左上角的「AI 识图」处理一张冰箱照片里的多种食材。冰箱为空或已有食材时，均从同一入口打开。未登录和非会员也能看到入口，打开后才检查资格。右上角的相机是离线「拍照识别」，继续用 Apple Vision 识别包装文字、日期及常见蔬果，不需要联网、登录或会员。

照片读取、联网识别失败或未识别出食材时，识图页顶部会弹出提示。提示消失后，页面仍会说明情况并提供重试或换图。换图直接回到拍摄和选图，无需重新等候资格预检。每次收到上传的照片后，CloudBase 都会在转发给模型前重新核对会员资格。

AI 识图需要联网和有效云乐坊会员。App 在拍照或选照片前核对资格；未登录时提示登录，非会员可选择「查看会员权益」或「继续使用离线拍照」。会员资格暂不可读时可以重试或走离线流程。相机和照片选择器都可以提供一张照片。App 在本机旋转、缩小并重新编码为 JPEG，去除原图位置等元数据。用户预览后每次都需要点按「同意并上传识别」；同意前取消或换图不会送出照片。模型只建议食材名；用户可以改名、取消勾选或取消整批操作。确认后每种食材记一份，批量保存复用手动添加的建议日期、自动分类及原有同步，不新增 CloudKit 字段。模型不会生成真实包装日期、数量或食用安全判断。

服务端可选择阿里云百炼或 DeepSeek。App 预览时查询当前接收方，查询不发送照片或账号；确认页点名展示该接收方。上传与用户同意的提供方绑定。如果切换发生在预览和上传之间，云函数不转发照片、不计入额度，App 更新接收方后需要用户再次确认。模型故障时不会自动切换提供方。

带登录令牌的 `GET` 只核验当前账号，返回 `member`，不接收照片。每次 `POST` 都通过同一云乐坊账号服务重新核验会员有效期，再预留额度并转发照片；非会员或查验失败时不调用模型。未携带令牌的 `GET` 继续只返回接收方，旧客户端也须通过 `POST` 的会员校验。

## 开启服务

1. 在现有 `yunlefun-8g7ybcxc7345c490` 环境新建独立 Event Function `cook-fridge-vision`，为 `https://api.yunle.fun/cook-fridge-vision` 设置 HTTP `GET`（查询接收方）与 `POST`（识别）访问。函数源为 `cloudfunctions/cook-fridge-vision`，使用 Node.js 24.11。在独立目录预装生产依赖并随部署包上传，关闭云端依赖安装。生产网关实测可通过约 96 KiB 的 JSON 请求，约 128 KiB 返回 413；客户端与函数均将 JPEG 限制为 70 KiB，使编码后请求小于 94 KiB。访问日志、追踪和错误采集不记录请求体或授权头。
2. 创建 `cook_fridge_vision_quota` 集合，仅允许管理端／函数访问，为 `expiresAt` 建索引。每次原子预留全局和账号每日额度，按中国标准时间换日；服务按批回收过期计数。不复用文字 AI 的额度或数据库记录。
3. 设置 `COOK_FRIDGE_VISION_PROVIDER`，并只向函数注入对应的服务端 Key。另注入 `COOK_FRIDGE_VISION_QUOTA_SALT`（至少 32 字符的独立随机值），设置 `COOK_FRIDGE_VISION_ENABLED=true`。`COOK_FRIDGE_VISION_USER_DAILY_LIMIT` 和 `COOK_FRIDGE_VISION_DAILY_BUDGET` 默认每账号每日 8 次、全站每日 150 次。配置不完整时服务关闭。账号令牌仅用于向 CloudBase 核验身份，模型只收到经处理的图像和固定提示词。
4. 用有权使用的非私密冰箱测试图验证真实模型、无效会话和网关体积。原生构建设置 `COOK_FRIDGE_VISION_SERVICE_URL` 默认指向 `https://api.yunle.fun/cook-fridge-vision`；置空可隐藏 AI 识图入口，原有拍照流程照常工作。

| `COOK_FRIDGE_VISION_PROVIDER` | 模型                                                                                        | 服务端 Key                            |
| ----------------------------- | ------------------------------------------------------------------------------------------- | ------------------------------------- |
| `qwen`（默认）                | [阿里云百炼](https://help.aliyun.com/zh/model-studio/vision) `qwen-vl-plus`                 | `COOK_FRIDGE_VISION_API_KEY`          |
| `deepseek`                    | [DeepSeek 官方视觉 API](https://api-docs.deepseek.com/zh-cn/guides/vision) `deepseek-flash` | `COOK_FRIDGE_VISION_DEEPSEEK_API_KEY` |

冰箱识图使用独立的服务端配置，不读取手机上为智能找菜或步骤讲解保存的个人 DeepSeek Key。切换提供方无需重新打包 App；两个通道共享识图额度。

本地可运行 `node --test test/cookFridgeVision.test.mjs` 验证 HTTP 契约和额度；iOS `FridgeVisionTests` 验证图像处理及上传契约，`FridgeVisionUITests` 使用内存测试图和替代响应验证同意与批量入库，不调用线上模型。

## 生产验收 · 2026-11-17

`cook-fridge-vision` 已在 CloudBase 上使用 Node.js 24.11，接收方查询返回 `deepseek`，未登录的识图请求返回 401。函数只添加到 `api.yunle.fun` 的对应路径，原有路由保持不变。首次使用完整测试照时网关返回 413；调整压缩后，实际生产请求返回 200，识别出鸡蛋、番茄、胡萝卜、西兰花和黄瓜。测试身份的会话已退出。

iPhone 18 Pro / iOS 27.0 模拟器使用同一张图和这次的真实候选，在确认页取消三样，点击「加入冰箱（2 样）」后只看到鸡蛋、番茄。5 项 iOS 单元测试和这条界面流程通过。本轮生产请求和模拟器勾选分段验证，没有在模拟器中验证相机拍摄或原生 SSO，也没有分发新构建。

[测试照片](./evidence/fridge-vision-deepseek-2026-11-17.jpg) · [验收记录](./evidence/fridge-vision-deepseek-2026-11-17.json)

同日后续已上线会员门禁。用生产受管测试账号复测：非会员的资格查询返回 `member: false`，上传返回 `403 MEMBERSHIP_REQUIRED`；有效会员的资格查询返回 `member: true`，同一张照片仍识别出五种食材。两次会话均已退出。模拟器验证了非会员查看权益后可直接进入离线拍照，以及会员的勾选入库流程。
