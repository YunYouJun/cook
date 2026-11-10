# #174 / #186 关闭前复核

复核日期：2026-11-09。冻结构建为 `2.0.0 (14)`／源码 `1b456042`，准备时主线基线 `b425e923`。维护者已明确认可本页内容、授权内部上传，并采用已有模拟器证据收尾，真机项目保留未验证；内容批准来自维护者本次确认，独立于 AI 的一致性校验。

## 本版交付范围

- 三个饮食目标：控制体重 10 道、减少添加糖 12 道、均衡搭配 36 道，按 ID 合计 36 道；次日便当 10 道，与控制体重交集 9 道。
- Web 已由 #208 发布；iOS 相同审核数据已包含在仅内部候选 14。机构低 GI 场景为 GI News / University of Sydney 原方 10 道，来源与配方条件见[逐菜记录](./institutional-low-gi.md)。
- 控制体重仅提供原方份数和额外烹调油参考；保留克／毫升原单位，不表示低热量、低脂或一定能减重。减少添加糖不等于总糖为零或低 GI；均衡搭配是食物类别及补充建议，不是整餐营养达标结论。
- 机构低 GI 是机构栏目原方参考，无整菜人体实测 GI／GL 数值；替换米种、面种、比例或熟度后不继承标注。英文原方需联网阅读，中文权威资料只解释概念和测定方法。
- 完整热量／脂肪阈值、整菜人体实测 GI 和新增疾病饮食不属于本版交付。#186 按已确认的机构原方参考范围验收，不用机构栏目替代实测结果。

## 当前事实与证据

生产函数已下载核对：5 个源文件与 main 逐字节一致，当前 `353bc8dab0ea`，保留 `faf919fc0041`、`1878cc5772dc`；旧的“生产契约未部署”阻塞已解除。本轮没有部署或发送计费模型请求；未执行真实会员能力调用，包一致性不能替代该调用结果。公开接口的 GET 405、未登录能力请求 401 符合既有边界。

候选 IPA 的原散列未变，重新通过签名／内部限制／隐私核验；11 个内置内容文件与重建数据逐字节一致，元数据仅生成时间戳 `version` 不同，其他字段及 `dataVersion` 相同。数据、证据锚点和生成契约校验通过，38 项 AI 契约测试及 16 项发布门禁测试通过。[脱敏证据](../../docs/dev/evidence/dietary-readiness-2026-11-09.json)保留文件散列和实际未执行范围；旧版[收尾证据](../../docs/dev/evidence/dietary-release-closeout-2026-11-09.json)是当日早间历史快照，不覆盖本次生产回查。

构建 7 继续独立外测，限 30 人；构建 14 已按授权上传同一 IPA 并完成既有内部组分发，Build ID `d0fe231d-350f-4dd6-9df6-8e98bb468a0e`，状态 `VALID / INTERNAL_ONLY / IN_BETA_TESTING`，外部 `NOT_APPLICABLE`。中文说明已写入并回读一致，Apple 移除了文本末尾换行。外测 `candidate.json` 与仅内部 `internal-candidate.json` 分开，外测控制器在访问 Apple 前拒绝内部／未就绪候选。[分发证据](../../docs/dev/evidence/dietary-build14-published-2026-11-09.json)包含维护者明确批准、真实服务器状态及保留未验证的真机项目。

## 当前 36 道内容复核索引

下表直接来自当前 `recipe-dietary.json`，原方链接固定到内置正文的 HowToCook 提交 `0e106eea348c734fe1586f781202994439cf98a5`；通过／失败／未知针对各目标分别记录。油量为制作整份原方时额外加入的烹调油，未换算总脂肪、能量或人体吸收量。完整原料、未知复合配料、原文定位及复核日期见[审核数据](../../packages/data/recipe-dietary.json)，份量用油依据见[审核说明](./audit.md)，便当冷藏运输与复热条件见[指南](../../docs/guide/next-day-lunch.md)。

| ID  | 原方                                                                                                                                                                  | 控制体重 | 减少添加糖 | 均衡搭配 | 原方人数／整份额外用油 |
| --- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------- | ---------- | -------- | ---------------------- |
| 333 | [西葫芦炒蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/西葫芦炒鸡蛋/西葫芦炒鸡蛋.md)                | 通过     | 通过       | 通过     | 2 人／10–20ml          |
| 489 | [罗宋汤](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/soup/罗宋汤.md)                                                 | 未知     | 未知       | 通过     | 未审核                 |
| 567 | [洋葱炒鸡蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/洋葱炒鸡蛋/洋葱炒鸡蛋.md)                    | 未知     | 未知       | 通过     | 未审核                 |
| 630 | [菠菜炒鸡蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/菠菜炒鸡蛋/菠菜炒鸡蛋.md)                    | 通过     | 通过       | 通过     | 1 人／15ml             |
| 670 | [印度土豆花菜](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/印度土豆花菜.md)                           | 未知     | 未知       | 通过     | 未审核                 |
| 677 | [清炒花菜](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/清炒花菜.md)                                   | 通过     | 通过       | 通过     | 2 人／15ml             |
| 689 | [蒜蓉炒芹菜](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/蒜蓉炒芹菜/蒜蓉炒芹菜.md)                    | 未知     | 通过       | 通过     | 未审核                 |
| 693 | [金针菇日本豆腐煲](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/金针菇日本豆腐煲.md)                   | 未知     | 失败       | 通过     | 未审核                 |
| 696 | [韭菜炒蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/韭菜炒蛋/韭菜炒蛋.md)                          | 未知     | 通过       | 通过     | 未审核                 |
| 701 | [冬瓜桑拿鸡](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/meat_dish/冬瓜桑拿鸡.md)                                    | 通过     | 未知       | 通过     | 2 人／10ml             |
| 702 | [冬瓜酿肉](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/meat_dish/冬瓜酿肉/冬瓜酿肉.md)                               | 未知     | 未知       | 通过     | 未审核                 |
| 731 | [澳门湿版免治牛肉饭](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/meat_dish/澳门湿版免治牛肉饭/澳门湿版免治牛肉饭.md) | 未知     | 失败       | 通过     | 未审核                 |
| 736 | [瘦肉土豆片](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/meat_dish/瘦肉土豆片/瘦肉土豆片.md)                         | 通过     | 未知       | 通过     | 1 人／10–15ml          |
| 748 | [肉饼炖蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/meat_dish/肉饼炖蛋.md)                                        | 未知     | 未知       | 通过     | 未审核                 |
| 756 | [蒜苔炒肉末](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/meat_dish/蒜苔炒肉末.md)                                    | 通过     | 未知       | 通过     | 1 人／10ml             |
| 777 | [黄瓜炒肉](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/meat_dish/黄瓜炒肉.md)                                        | 未知     | 未知       | 通过     | 未审核                 |
| 788 | [葱油桂鱼](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/aquatic/葱油桂鱼/葱油桂鱼.md)                                 | 未知     | 未知       | 通过     | 未审核                 |
| 789 | [蒜蓉虾](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/aquatic/蒜蓉虾/蒜蓉虾.md)                                       | 未知     | 未知       | 通过     | 未审核                 |
| 798 | [山药南瓜炖鸡汤](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/soup/山药南瓜炖鸡汤.md)                                 | 通过     | 未知       | 通过     | 2 人／0g               |
| 799 | [排骨山药玉米汤](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/soup/排骨山药玉米汤/排骨山药玉米汤.md)                  | 通过     | 未知       | 通过     | 2 人／10ml             |
| 800 | [排骨苦瓜汤](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/soup/排骨苦瓜汤/排骨苦瓜汤.md)                              | 通过     | 通过       | 通过     | 4 人／0g               |
| 805 | [紫菜蛋花汤](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/soup/紫菜蛋花汤.md)                                         | 未知     | 通过       | 通过     | 未审核                 |
| 809 | [黄瓜皮蛋汤](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/soup/黄瓜皮蛋汤.md)                                         | 未知     | 未知       | 通过     | 未审核                 |
| 814 | [意式肉酱面](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/staple/意式肉酱面/意式肉酱面.md)                            | 通过     | 未知       | 通过     | 2 人／10–15ml          |
| 829 | [电饭煲三文鱼炊饭](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/staple/电饭煲三文鱼炊饭/电饭煲三文鱼炊饭.md)          | 未知     | 通过       | 通过     | 未审核                 |
| 840 | [韭菜盒子](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/staple/韭菜盒子.md)                                           | 未知     | 未知       | 通过     | 未审核                 |
| 844 | [太阳蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/太阳蛋.md)                                            | 未知     | 通过       | 通过     | 未审核                 |
| 846 | [微波炉荷包蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/微波炉荷包蛋.md)                                | 未知     | 通过       | 通过     | 未审核                 |
| 849 | [水煮玉米](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/水煮玉米.md)                                        | 未知     | 失败       | 通过     | 未审核                 |
| 850 | [溏心蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/溏心蛋.md)                                            | 未知     | 通过       | 通过     | 未审核                 |
| 851 | [燕麦鸡蛋饼](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/燕麦鸡蛋饼.md)                                    | 未知     | 通过       | 通过     | 未审核                 |
| 852 | [空气炸锅面包片](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/空气炸锅面包片.md)                            | 未知     | 未知       | 通过     | 未审核                 |
| 853 | [美式炒蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/美式炒蛋.md)                                        | 未知     | 未知       | 通过     | 未审核                 |
| 854 | [蒸水蛋](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/蒸水蛋.md)                                            | 未知     | 未知       | 通过     | 未审核                 |
| 857 | [鸡蛋三明治](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/breakfast/鸡蛋三明治.md)                                    | 未知     | 未知       | 通过     | 未审核                 |
| 861 | [素炒豆角](https://github.com/Anduin2017/HowToCook/blob/0e106eea348c734fe1586f781202994439cf98a5/dishes/vegetable_dish/素炒豆角.md)                                   | 未知     | 未知       | 通过     | 未审核                 |

## 关闭条件

| 项目                   | 当前状态         | 记录                                                                                                 |
| ---------------------- | ---------------- | ---------------------------------------------------------------------------------------------------- |
| 数据、源文件与候选绑定 | 已核对           | 原 IPA 未变；11 个内置内容文件一致，元数据仅重建时间戳不同                                           |
| Web 发布               | 已完成           | 复用 #208 的正式站点结果                                                                             |
| 生产新旧契约兼容       | 已核对           | 当前及两个旧版包一致；本轮无重复部署或计费调用                                                       |
| 维护者内容批准         | 已确认           | 维护者认可本版内容与公开说明，包含 36 道审核及 10 道机构原方                                         |
| 本轮使用验收           | 按模拟器证据收尾 | 维护者明确确认；真人 VoiceOver、真实飞行模式／网络恢复和真机冷启动仍未验证，留作后续复核             |
| PR #211                | 已合并           | 最终 16 项检查通过、1 项按策略跳过；合并树与已验证 head 一致，合并提交 `8e0a3267`                    |
| iOS 内部分发           | 已验证           | 原 IPA 仅上传一次，既有内部组和 1 名成员；VALID、INTERNAL_ONLY、IN_BETA_TESTING、外部 NOT_APPLICABLE |

维护者原话：「认可本版内容，授权构建 14 内部上传，按已有模拟器证据收尾，真机项目保留未验证。CI 通过后合并 #211，完成分发验证后关闭 #174、#186。」

本版范围的关闭条件已满足；实际 issue 状态由 GitHub 记录。完整热量／脂肪阈值、整菜人体实测 GI 及保留的真机复核仍不计为已实现或已通过。当前发布不改变外测构建 7、不新增测试员、不显式通知，也未操作维护者手机。
