# 0009 应用意见统一到 Support，保留菜谱质量反馈

- 状态：已采纳
- 日期：2026-10-23

## 决定

继续复用 `cook-feedback` 作为 Web 与原生 App 的入口：

| 入口                | 接收与管理                                    | 保留原因                                    |
| ------------------- | --------------------------------------------- | ------------------------------------------- |
| 应用意见 `feedback` | Support 私密反馈 API / `/manage?product=cook` | 一个处理状态与回执，避免两个后台重复收件    |
| 菜谱纠错 `report`   | `cook_recipe_feedback`                        | 结构化菜谱 ID、问题类型，供菜谱数据修复使用 |
| 搜索统计 `signal`   | `cook_search_signals`                         | 匿名聚合的推荐评测输入，不是客服工单        |
| 支持中心跳转        | Support，预选 cook                            | 无需 GitHub 账号的备用入口和公开需求投票    |

应用意见不再写 `cook_app_feedback`，旧记录仍保留并可用原导出读取。本次不迁移历史原文，
也不把菜谱纠错再抄送 Support。这样两个数据闭环各有明确用途，不会产生双写与双重处理状态。

## 身份与私密性

云函数通过 Support SDK 0.2.0 的 Node 入口签名。签名账号只取 CloudBase 的可信调用上下文；
HTTP 入口一律视为访客，不接受请求正文/请求头自报的 UID。匿名设备标识只在 Cook 服务端派生摘要，
Support 再按应用隔离并标记为 guest，不能成为账号或管理凭证。公钥注册只允许 cook 和 feedback:submit，
显式开启 allowGuests；私钥只在云函数环境变量中。

私密意见、选填联系方式进入 Support，公开需求由团队重新整理。提交回执只含编号、产品、状态与时间。
错误不回写旧表、不假装成功；Web 保留草稿与幂等编号，重试使用相同编号。旧 iOS 未提供编号时，
使用调用者、上海日期和完整内容的摘要去重（跨日期重新提交可能生成新反馈）。

Web 表单复用 YunLeFun Design，保留 Ionic 页面导航。原生 iOS 保留 SwiftUI 表单和相同 HTTP 协议；
后端升级即可统一新反馈，新的支持中心文案随下一次原生版本发布。

## 部署

先部署支持匿名委托的 Support，再配置 cook-server 公钥注册与云函数私钥。

```bash
node scripts/prepare-support-feedback.mjs /absolute/task-temp/functions
cd /absolute/task-temp/functions
tcb fn code update cook-feedback -e yunlefun-8g7ybcxc7345c490
```

脚本通过已登录 GitHub CLI 下载私有 Release 的 SDK，并检查固定 SHA-256。
也可传入第二个参数指定同一 SDK 包。准备目录不得已存在；生成物需要任务结束后回收。
公开 Cook 仓库只记录版本和校验值，不提交私有 SDK 源码或 tarball，Web 构建不依赖私有仓库。
脚本会在隔离目录安装依赖并生成 installDependency: false 的部署配置。使用代码更新命令打包 node_modules，
保持 Nodejs18.15，不让云端再次安装依赖；旧 MCP updateFunctionCode 无法传递该选项，会触发依赖包获取失败。
保留所有既有环境变量，并添加 `COOK_SUPPORT_PRIVATE_KEY`（ES256 PEM）、`COOK_SUPPORT_KEY_ID`。
函数超时设为 30 秒，SDK 出站请求设为 8 秒，兼容原生 App 现有 10 秒超时。

发布前检查：Web 类型/测试/静态构建；HTTP 旧协议、SDK 签名与错误映射；真实匿名与账号提交；
回执幂等、Support 私密队列和公开 DTO 不泄露原文；菜谱纠错和搜索统计回归。回退时整体恢复旧云函数
部署包与环境快照，Web 先回退至旧版本（避免旧后端返回无回执的成功）。
