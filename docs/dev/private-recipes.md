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
- 库中有无法识别的数据时应保留原始存储并报告错误，不静默丢弃。菜谱 JSON 是文本和链接，不含图片/视频附件和公共投稿。

实现：`packages/cook/src/utils/custom-recipes.ts` 和 `CookCore/PrivateRecipes.swift`；共享样例为 `packages/data/fixtures/private-recipes.json`。`bash scripts/test-private-recipe-roundtrip.sh` 实际执行 Web 导出 → Swift 解析/导出 → Web 比较，并运行冲突、去重、坏文件用例。所有测试使用合成数据。

## iOS 持久化与验收边界

新增 `PrivateRecipeRecord` 模型，不改内置菜谱的整数编号，也不迁移收藏/历史引用。JSON payload 作为一个字段存储，所有必填属性都有默认值、无唯一约束，继续由 SwiftData 的现有 CloudKit 私有库同步。重复记录按更新时间取最新，时间相同优先删除；编辑打开后发现本机已收到其他改动时拒绝覆盖并保留编辑表单。系统级离线并发编辑仍遵循 CloudKit 合并行为，首版不提供多版本编辑历史。

删除清空所有重复记录的正文，只保留编号与时间组成的 tombstone，旧文件遇到同一编号只可明确另存副本；文件导出不包含 tombstone。文件导入整批验证再保存，失败不提交一部分；Web 保存前读取最新本地存储，配额不足不报告成功。离线隐私政策同步说明私人菜谱字段、文件迁移和删除标记的保留方式。

已整合 #112 的 CloudKit 初始化机制（schema 2 含 FridgeItem），版本已升到 3，新增 PrivateRecipeRecord。需维护者在 Development 验证后另行决定 Production schema 部署。本功能的代码和模拟器测试不能证明生产 iCloud 已经可用。

维护者于 2026-10-24 明确将本轮源码合并条件限定为代码与模拟器验收：与最新 main 集成后，代码检查、Web/Swift 文件往返和 iPhone/iPad 模拟器测试全部通过。模拟器中的本地保存、迁移和进程重启结果不代表生产 CloudKit 已通过验收，源码合并也不代表发布 App。

发布前仍须验证：同 Apple 账号两台真实设备的新建、修改、删除和离线并发修改/删除后重连；未登录和退出 iCloud 后的本地数据保留；另一 Apple 账号的数据隔离；当前五个模型的 Development/Production schema 匹配及生产环境实际读写。这些项目目前未验证，继续作为发布门禁，不由模拟器结果替代。

当前实现不含 Web 云同步、公共投稿，以及 iOS 私人菜谱参与内置推荐、收藏或历史；Web 原有参与推荐/随机选菜的行为保留。范围与验收决定见 [0013 私人菜谱与跨端文件迁移](../decisions/0013-private-recipes)。

## 个人食材和厨具

「我的 → 食材和厨具」支持按类别添加、删除自定义名称，以及隐藏和恢复内置条目。配置会用于经典和工作台模式的选择、搜索、拖入校验、输入联想和私人菜谱编辑；iOS 冰箱选择器也共用配置。隐藏只影响候选列表，已选食材/厨具仍有取消入口，忌口独立保留。新标签不会凭空生成新的公共菜谱，网页版会用它们匹配自己的菜谱。设计见 [0014 个人食材和厨具](../decisions/0014-personal-catalog)。

个人配置的备份为独立 JSON：`{ app: "cook", kind: "personal-catalog", version: 1, items, hidden }`，每项 `{ category, name }`。类别为 `vegetable/meat/staple/tools`，每列最多 500 项，名称 1–30 UTF-16 单元，文件最多 256 KB。导入确认后合并，重复名称保留本机类别。iOS 原子文件、Web localStorage 都先验证后保存；出错保留原文件，不修改菜谱 v1 或 CloudKit schema。共享用例 `packages/data/fixtures/personal-catalog.json`，`bash scripts/test-personal-catalog-roundtrip.sh` 执行两端真实导出器的往返测试。

## 菜谱来源

视频通过填写在线链接或 BV 号记录，不导入视频文件，也不提供内嵌播放器。有来源链接的私人菜谱点击名称会直接打开 B 站或用户的网页；「详情」保留备注和编辑入口。没有链接时点击名称进入详情。
