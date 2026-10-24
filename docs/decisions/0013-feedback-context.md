# 0013 菜谱反馈上下文与测试隔离

- 状态：已采纳
- 日期：2026-10-24

## 用户入口

Web 做菜结果的菜谱详情提供「反馈这道菜的问题」；菜谱列表、收藏和历史记录保留直接打开做法，同时增加「反馈」按钮。反馈表单固定携带所选菜名、编号及视频 / 图文链接，不依赖用户再次输入菜名。iOS 菜谱详情进入同样的原生表单。

选择问题类型后先填写 / 确认再提交。所有类型可补充 200 字说明；「其他」需要说明。视频或图文访问问题可选填具体表现：内容下架、加载失败、需要登录、打开了错误内容等。失败保留草稿，可重试；正式用户也可打开带菜谱信息的邮件草稿。匿名反馈继续可用。

## 最小诊断信息

菜谱纠错保存 `linkIssue`、`version`、`context.entryPoint/origin/path/build/recipeVersion` 和接收端确定的 `environment`。Web 路径不包含查询参数或片段；iOS 使用固定入口路径。菜谱版本是构建时 `recipe.csv` 的 SHA-256 前 12 位。只接受有界、允许的字段，不采集原始浏览器指纹、位置或额外联系方式。

入口、站点、平台和版本由客户端提供，是排查线索，不是外部真实用户的身份证明。只有登录 SDK 的服务端身份可关联账号；HTTP 反馈始终匿名。后台只显示匿名 / 已登录，不返回 UID 或 reporter 散列。

历史记录没有来源和版本时显示「未记录」，不补造元数据，不更新历史记录或其审核指纹。历史 `video` 的图文识别规则不变。

## 测试隔离

- 正式 Web 仅限 `https://cook.yunyoujun.cn` 与 `https://cook.yunle.fun` 的生产构建。本地、预览站、其他域名和开发构建均使用 `cook-feedback-test`，即使登录也走匿名测试 HTTP 接口。
- iOS Debug 将正式反馈地址自动替换为测试地址；Release 使用正式地址。UI / 单元测试继续注入本地 transport，不访问真实服务。TestFlight 的 Release 版本反馈属于正式用户反馈。
- 正式函数拒收 `environment: test` 和非正式浏览器 Origin。测试函数强制 `environment: test`，调用者无法选择数据集合；缺少或不匹配环境时拒绝。
- 测试数据分别进入 `cook_recipe_feedback_test`、`cook_search_signals_test`、`cook_feedback_quota_test`、`cook_app_feedback_test`，全部设为 `ADMINONLY`。测试应用意见只保存在测试集合，不转发正式 Support。
- admin 的菜谱审核与统计只读取正式集合。测试数据可由有权限的维护者在云数据库中查看。此处为同一 CloudBase 环境下的函数和集合隔离，不是新的付费云环境。
- 兼容未携带新字段的旧客户端；旧版无 Origin 的匿名请求无法被可靠识别为开发请求。上述来源字段也不作为反机器人身份认证。

## 部署与验证

`node scripts/prepare-support-feedback.mjs /absolute/temp/functions` 同时生成正式和测试函数包。测试入口在代码中固定测试模式，并使用独立配置；不复制正式 Support 密钥或导出令牌。

首次发布先创建上述四个测试集合并设置 `ADMINONLY`，再在临时函数根目录运行：

```bash
tcb fn deploy cook-feedback-test -e yunlefun-8g7ybcxc7345c490 --path /cook-feedback-test
tcb fn code update cook-feedback -e yunlefun-8g7ybcxc7345c490
```

后续测试函数发布使用 `tcb fn code update cook-feedback-test`，仅更新代码，保持测试配置。`installDependency: false` 保留已打包的私有 SDK；不使用会重新启用云端依赖安装的更新工具。部署前运行本地协议与隔离测试，之后只向测试端提交验收数据，再确认正式记录未变化。不得以正式反馈制造验收样本。
