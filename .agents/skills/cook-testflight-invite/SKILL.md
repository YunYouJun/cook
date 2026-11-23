---
name: cook-testflight-invite
description: 手动检查云食册外测报名，并通过 App Store Connect 向已收到 QQ 群邀请的报名者发出首批外测组的 TestFlight 定向邮件。适用于明确要求预览、发送某条报名的邀请或手动发一批邀请。
---

# 云食册 TestFlight 定向邀请

在已配置飞书和 Apple 授权的 Mac 上，从 Cook 仓库根目录运行。脚本复用本机的邀请去重记录，飞书的 QQ 群回信由现有流程处理。

1. 先预览本次范围和实时名额。

   ```bash
   PYTHONDONTWRITEBYTECODE=1 /opt/homebrew/bin/python3 scripts/testflight/invite_signups.py --testflight
   ```

   指定单条报名时，先核对飞书记录，用真实记录 ID 传 `--record-id RECORD_ID`。指定人数时传 `--limit N`，按报名时间处理。预览中的待处理人数是筛选后的队列大小，发送上限还受实时名额和 `--limit` 控制。

2. 用户明确要求发邀请时，在同一条命令后加 `--apply`。Apple 向报名邮箱发送专属安装链接。脚本查重并核对实时容量，在 Apple 确认后回写飞书「已邀请」。只要求预览时，返回摘要。
3. 汇报新发邀请、已回写及待确认数量。满额的记录保留在「已发 QQ 群邀请」，待下次手动触发时重新核对。超时或状态不明时保留账本，后续用同一入口查询回执，避免补发。报名邮箱和原始记录留在飞书，汇报使用脱敏摘要。

定向邀请和外测发版共用 `scripts/testflight/candidate.json` 中的首批外测组和已发布构建。脚本按邮件邀请成员统计本机配置的邀请名额，原公开链接的名额由 Apple 单独统计。Apple 的专属安装链接由 Apple 邮件送出。
