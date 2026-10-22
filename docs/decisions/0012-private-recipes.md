# 0012 私人菜谱与跨端文件迁移

- 状态：实现提案，随 PR 审阅
- 日期：2026-10-22
- 关联：[#77](https://github.com/YunYouJun/cook/issues/77)、[#101](https://github.com/YunYouJun/cook/pull/101)、[#112](https://github.com/YunYouJun/cook/pull/112)

## 决定

先完成 Web 与 iOS 私人菜谱的增删改和安全备份恢复，不引入新的 Web 云服务。使用独立的版本化 JSON 协议，保留 Web 现有 `customId`，不复用内置菜谱整数 id。现有收藏/历史协议保持不变。

iOS 新增 CloudKit 兼容的 SwiftData 模型，复用既有私有同步机制；iCloud 仅在同一 Apple 账号的 Apple 设备间同步，Web 与 iOS 通过用户选择的文件迁移。文件冲突默认跳过，也可显式保留两份，绝不按时间自动覆盖。

## 范围与取舍

食材/厨具为每道私人菜谱的文本字段。首版不修改公共词典、不提供全局私人词典或公开投稿。Web 原有匹配/随机行为保留；iOS 先提供独立“我的菜谱”，推荐/收藏/历史的私人身份扩展另行设计，避免用临时负数或哈希冒充永久 id。

后续产品决策：iOS 私人菜谱是否参与推荐及收藏；私人词典的改名/删除影响范围；是否增加 Web 账号云同步；是否提供并发编辑版本历史。CloudKit Production schema 部署和真机验收是独立发布动作，本 PR 不执行。

协议、迁移和验收说明见 [私人菜谱与文件迁移](/dev/private-recipes)。
