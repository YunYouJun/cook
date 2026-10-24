/** 人工核对的明确冲突/未定义单位；只在原句仍完全一致时适用。 */
export const recipeAmountReviewNotes = [
  {
    id: 727,
    quotes: ['水 10 g', '打入一个鸡蛋，搅拌，再分次加入水 100 g ，再加 10 g 油，反复搅拌。直到炸糊完全调开，略粘稠即可。'],
    categories: ['overuse', 'prep_calculation_scope'],
    reason: '计算表每份水为 10g，炸糊步骤却写 100g；淀粉 20+60g 已能确定，不代表水量也已确定。',
    confirmation: '确认炸糊水应为每份 10g 还是 100g，或补充其他份数/批次依据；现阶段保留两个原数。',
  },
  {
    id: 708,
    quotes: ['黄豆酱 = 5g', '**封装酱汁碗（一键调用）：** 拿一个小碗，依次加入 10ml 生抽、5ml 老抽、5ml 蚝油、5ml 黄豆酱，彻底搅拌均匀，放在灶台触手可及处备用。'],
    categories: ['incompatible_units'],
    reason: '计算表黄豆酱为 5g，步骤为 5ml，没有本菜谱密度定义，不能互换或确定应采用哪一个。',
    confirmation: '确认黄豆酱按质量 5g 还是体积 5ml；料酒 15+10ml 与葱姜分配的修复不改变这项冲突。',
  },
  {
    id: 851,
    quotes: ['牛奶 50-100g，能够将燕麦搅拌粘稠即可', '牛奶一盒 约 250ml', '出锅，搭配剩下的牛奶作为早餐。'],
    categories: ['incompatible_units', 'remaining'],
    reason: '一盒约 250ml 是早餐总包装，拌燕麦用 50–100g 并依稠度调整；没有质量/体积换算，也没有实际取用值，不能算确定剩余牛奶。',
    confirmation: '保留包装量、拌糊参考范围与原文早餐余奶说明；若要自动算余量，需同维度总量及实际取用量。',
  },
  {
    id: 828,
    quotes: ['猪油 15 g（添加蚝油时，减少到 10 g）', '生抽 5 ml（添加蚝油时，减少到 3 ml）', '淋上酱油与蚝油'],
    categories: ['optional_condition', 'alias_or_reference'],
    reason: '猪油与生抽的当前量取决于是否加入蚝油，步骤又将生抽/老抽笼称酱油；没有选择分支时不能把默认参考量当作实际投料。',
    confirmation: '确认本次是否加蚝油，以及步骤的酱油指生抽、老抽还是两者；保留原文两套每份量，不自动选分支。',
  },
  {
    id: 802,
    quotes: ['胡椒粉量 = 猪肉斤数 * 2 克', '每斤肉，6 克盐，1 克胡椒粉'],
    categories: ['overuse', 'unknown_formula'],
    reason: '计算表写每斤肉 2 克胡椒粉，调味步骤写每斤肉 1 克；同时没有给出肉的总重量，不能决定胡椒粉总量。',
    confirmation: '确认胡椒粉应按每斤肉 1 克还是 2 克，以及实际猪肉重量；盐还需区分丸子肉与汤底的用量。',
  },
  {
    id: 684,
    quotes: ['老豆腐 1 块 （市场买 1.25 格 * 人）'],
    categories: ['incompatible_units', 'alias_or_reference'],
    reason: '已按原文“每份 1 块”记录材料依赖，但市场“格”与菜谱“块”的换算、每份人数没有定义；不能将 1.25 格当作 1.25 块。',
    confirmation: '明确一块豆腐对应几格、每份几人，或提供每块重量；现有换算继续只用原文块数。',
  },
  {
    id: 768,
    quotes: ['淀粉 10g（可选，其中 5g 涂青椒内壁、5g 水淀粉勾芡）', '可选加入：料酒 10ml、蚝油 10ml、鸡蛋 1 个、淀粉 5g、白胡椒粉 2g、白糖 5g'],
    categories: ['overuse', 'optional_condition', 'repeated_addition'],
    reason: '原文总淀粉 10g 已分给涂壁与勾芡各 5g，肉馅步骤又可选加入 5g；尚不清楚这第三处是否包含在 10g 内，不把可选分支当必然消耗。',
    confirmation: '确认三处淀粉能否同时采用，总量应为 10g 还是采用全部分支时 15g，以及所选方案。',
  },
  {
    id: 695,
    quotes: ['注意，受食材个体影响，本菜谱为 2 人份菜谱', '豆角 300g * 2 人'],
    categories: ['missing_serving_basis', 'unknown_formula'],
    reason: '正文说明为 2 人份，但数量又写“300g * 2 人”；不清楚 300g 是两人的总量还是人均量，不能选择 300g 或 600g。',
    confirmation: '确认这一组乘“2 人”的材料是每人用量还是整道菜总量，并给出与葱姜蒜等固定项的份数关系。',
  },
] as const
