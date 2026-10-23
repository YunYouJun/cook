# 私人菜谱与文件迁移

Web 的「我的 → 我的菜谱」保存在本机。导出私人菜谱，再在另一浏览器或云食册 iOS 版的「我的菜谱」导入，可迁移食材、厨具、来源和备注。收藏/历史的备份是另一种文件，不能混用。

导入先预览新增、相同和冲突条目，不会覆盖原有菜谱。相同编号不同内容默认跳过，也可另存副本（新编号，保留 `copiedFrom`）；重复导入同一内容不会再生成副本。同名不同编号仍是不同菜谱。未知版本、未知菜谱字段或任意坏条目会拒绝整个文件，不会部分导入。

## 跨端契约 v1

`{ app: "cook", kind: "custom-recipes", version: 1, exportedAt, recipes }`。

每条含 `customId, name, stuff, tools, createdAt, updatedAt`；可选 `bv, link, note, tags, methods, difficulty, copiedFrom`。时间是 Unix 毫秒整数，非 Swift Date 的默认编码。内置菜谱的整数 id 不参与这个协议。旧 Web `cook:custom-recipes` 的 id 和创建时间保持不变，无需迁库。

- 最多 5000 条，UTF-8 文件最多 5 MB；菜名 30 个 UTF-16 单元、备注 20000；食材/厨具/标签/做法每列最多 64 项，每项最多 100 个 UTF-16 单元。
- 来源为合法 BV 号或 HTTP(S) URL，不接受脚本、文件 URL 或带用户名密码的链接。
- 私人数据不进入 Web CloudBase 收藏/历史协议，不自动上传到云乐坊。iOS 使用同一 Apple 账号的 iCloud 私有库，不等于 Web 账号同步。
- 文件导出只含当前有效菜谱，导入不是镜像同步，不会根据文件缺少的条目删除本机菜谱。iOS 遇到已删除的同一编号时作为冲突处理，可显式另存副本。
- 库中有无法识别的数据时应保留原始存储并报告错误，不静默丢弃。首版是文本和链接，不含图片/视频附件和公共投稿。

实现：`packages/cook/src/utils/custom-recipes.ts` 和 `CookCore/PrivateRecipes.swift`；共享样例为 `packages/data/fixtures/private-recipes.json`。`bash scripts/test-private-recipe-roundtrip.sh` 实际执行 Web 导出 → Swift 解析/导出 → Web 比较，并运行冲突、去重、坏文件用例。所有测试使用合成数据。

## iOS 持久化和上线验收

新增 `PrivateRecipeRecord` 模型，不改内置菜谱的整数编号，也不迁移收藏/历史引用。JSON payload 作为一个字段存储，所有必填属性都有默认值、无唯一约束，继续由 SwiftData 的现有 CloudKit 私有库同步。重复记录按更新时间取最新，时间相同优先删除；编辑打开后发现本机已收到其他改动时拒绝覆盖并保留编辑表单。系统级离线并发编辑仍遵循 CloudKit 合并行为，首版不提供多版本编辑历史。

删除清空所有重复记录的正文，只保留编号与时间组成的 tombstone，旧文件遇到同一编号只可明确另存副本；文件导出不包含 tombstone。文件导入整批验证再保存，失败不提交一部分；Web 保存前读取最新本地存储，配额不足不报告成功。离线隐私政策同步说明私人菜谱字段、文件迁移和删除标记的保留方式。

已整合 #112 的 CloudKit 初始化机制（schema 2 含 FridgeItem），版本已升到 3，新增 PrivateRecipeRecord。需维护者在 Development 验证后另行决定 Production schema 部署。本功能的代码和模拟器测试不能证明生产 iCloud 已经可用。

合并/发布前验收：同 Apple 账号两台真实设备新建、修改、删除、离线编辑后重连；未登录/退出 iCloud 的本地数据保留；另一 Apple 账号隔离；Development/Production schema 匹配。当前实现不含 Web 云同步、公共投稿、全局私人食材/厨具词典，以及 iOS 私人菜谱参与内置推荐、收藏或历史；Web 原有参与推荐/随机选菜的行为保留。
