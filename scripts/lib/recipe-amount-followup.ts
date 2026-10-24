import type { RecipeTextData } from '../../packages/cook/src/types/recipe-text'
import type { AmountAnchor, AmountAnnotations, AmountFormula, AmountUsage } from './recipe-amounts'
import { anchorOf, divide, literal, multiply, scalar, servings, sourceFingerprint, sourceLines } from './recipe-amounts'

/** 第二轮逐篇复核；冻结整篇依据，来源变化时必须重新核对，不能自动重签这些分配。 */
const reviewedFingerprints: Record<string, string> = {
  489: 'dd5acaa67ca1fac7',
  598: '21e127f00de96f6b',
  641: '40862bd6ba4d71a7',
  680: '3cafddf08f14e073',
  701: '23a597b3522a9ba3',
  708: 'c89ef9df97acce06',
  710: '8aea0a5e9d76d723',
  727: '7efa2bcf49015307',
  731: '221c5fd5950ae4fa',
  742: '7497b2bd537fd48f',
  744: 'aa15e4755a50c370',
  750: 'e5a9936353c9dc60',
  754: '6ba34eeb35099596',
  775: '7732ce39fc88b000',
  781: '4eb4d1cdb4c01d25',
  799: '10bf3a1c67762219',
  810: '8a21d51832564282',
  817: '8411e597c476a4de',
}

export interface ReviewedAmountRelation {
  id: number
  ingredient: string
  complete: boolean
  explanation: string
  evidence: AmountAnchor[]
  usages: AmountUsage[]
}
type Position = [block: number, item: number]
interface Binding { at: Position, target: string, kind?: AmountUsage['kind'], formula?: AmountFormula, conditional?: boolean, display?: boolean }
const ref = (name: string): AmountFormula => ({ op: 'reference', name })
const part = (name: string, numerator: number, denominator: number) => divide(multiply(ref(name), scalar(numerator)), scalar(denominator))
const per = (amount: number, unit: string) => multiply(literal(amount, unit), servings)
const amount = (at: Position, target: string, formula: AmountFormula, conditional = false): Binding => ({ at, target, formula, conditional })
const reference = (at: Position, target: string): Binding => ({ at, target, kind: 'reference' })

export function applyReviewedAmountAllocations(source: RecipeTextData, data: AmountAnnotations): ReviewedAmountRelation[] {
  for (const [id, fingerprint] of Object.entries(reviewedFingerprints)) {
    if (!source.recipes[id] || sourceFingerprint(source.recipes[id]!) !== fingerprint)
      throw new Error(`${id} 第二轮分配依据已变化，需重新逐篇复核`)
  }
  const reviews: ReviewedAmountRelation[] = []
  const anchor = (id: number, at: Position) => {
    const line = sourceLines(source.recipes[id]!).find(l => l.block === at[0] && l.item === at[1])
    if (!line)
      throw new Error(`${id} 缺少原文位置 ${at}`)
    return anchorOf(line)
  }
  const quantity = (id: number, name: string, formula: AmountFormula, positions: Position[], note: string, conditional = false) => {
    const entry = data.recipes[id]!
    entry.quantities = entry.quantities.filter(q => q.name !== name)
    entry.quantities.push({ name, formula, evidence: positions.map(at => anchor(id, at)), aliases: [], note, conditional })
  }
  const bind = (id: number, ingredient: string, bindings: Binding[], positions: Position[], explanation: string, complete = true) => {
    const entry = data.recipes[id]!
    const evidence = [...positions.map(at => anchor(id, at)), ...(entry.quantities.find(q => q.name === ingredient)?.evidence ?? [])]
    const usages: AmountUsage[] = bindings.map((binding) => {
      const location = anchor(id, binding.at)
      if (!binding.target || location.text.split(binding.target).length !== 2)
        throw new Error(`${id} ${ingredient} 分配片段不唯一：${binding.target}`)
      return { anchor: location, ingredient, kind: binding.kind ?? 'amount', target: binding.target, ...(binding.formula ? { formula: binding.formula } : {}), ...(binding.display === undefined ? {} : { display: binding.display }), conditional: binding.conditional ?? false, evidence: [location, ...evidence] }
    })
    entry.usages = entry.usages.filter(u => u.ingredient !== ingredient)
    entry.usages.push(...usages)
    reviews.push({ id, ingredient, complete, explanation, evidence, usages })
  }

  // 固定配方没有份数基准，只分解 3g / 10g，不把它们乘人数。
  bind(598, '食盐', [amount([9, 3], '一半的食盐', part('食盐', 1, 2)), { at: [9, 9], target: '剩下的食盐', kind: 'remaining' }], [[7, 8], [9, 3], [9, 9]], '固定批次食盐 3g：腌肉一半，豆腐阶段使用剩余一半。')
  bind(598, '酱油', [amount([9, 3], '味极鲜酱油', part('酱油', 1, 2)), { at: [9, 9], target: '味极鲜酱油酱油', kind: 'remaining' }], [[5, 10], [7, 9], [9, 3], [9, 9]], '原料明确酱油为味极鲜；腌肉与豆腐两处各半，原文重复的“酱油”不改写。')

  bind(489, '盐', [amount([9, 3], '盐 3 g', part('盐', 3, 18)), { at: [15, 2], target: '剩余 15 g 盐', kind: 'remaining' }], [[7, 12], [9, 3], [15, 2]], '每份 18g 盐明确分为腌牛肉 3g、后段剩余 15g；随配方份数同步。')
  for (const [name, target] of [['水', '混合水与淀粉成水淀粉'], ['淀粉', '混合水与淀粉成水淀粉']] as const)
    bind(641, name, [amount([9, 3], target, ref(name))], [[7, name === '水' ? 7 : 6]], '每份水 20ml 与淀粉 5g 专用于同一水淀粉；混合和倒入是同一批材料，不再扣一次。')
  for (const name of ['白砂糖', '盐'])
    bind(641, name, [{ at: [9, 2], target: name, kind: 'total' }], [], '完整步骤中仅在此处加入该定量调味料。')
  bind(641, '熟松子仁', [{ at: [9, 4], target: '熟松仁', kind: 'total' }], [[7, 1], [9, 4]], '原料熟松子仁与步骤熟松仁指同一食材；完整步骤仅一次加入。')

  bind(680, '低筋面粉', [amount([14, 0], '40g 低筋面粉', part('低筋面粉', 1, 3)), { at: [14, 1], target: '剩余 80g 低筋面粉', kind: 'remaining' }], [[8, 2], [14, 0], [14, 1]], '每份面粉 120g 明示首次拍粉 40g、混合粉 80g；混合粉补撒不是再配一份。')
  for (const [name, target, item] of [['玉米淀粉', '40g 玉米淀粉', 3], ['辣椒粉', '2g 辣椒粉', 5], ['黑胡椒粉', '1g 黑胡椒粉', 6], ['食盐', '3g 食盐', 7]] as const)
    bind(680, name, [amount([14, 1], target, ref(name), name === '辣椒粉')], [[8, item]], '计算表与混合粉的唯一投料量一致；辣椒粉仍可省略，混合粉补撒不另计一批。')
  bind(727, '淀粉', [amount([9, 2], '20 g 淀粉（注意是 20 g 淀粉，剩下 60 g 备用）', part('淀粉', 1, 4)), { at: [9, 4], target: '剩余的 60 g 淀粉', kind: 'remaining' }], [[7, 6], [9, 2], [9, 4]], '每份淀粉 80g：炸糊 20g、拍粉 60g。椒盐粉的两次分配以及水 10g/100g 冲突仍不补猜。')

  bind(710, '油', [amount([10, 1], '5ml 油', part('油', 1, 7)), { at: [10, 7], target: '剩余的油', kind: 'remaining' }, reference([10, 8], '热油'), reference([10, 10], '姜葱姜油')], [[8, 6], [10, 1], [10, 7], [10, 8], [10, 10]], '每份 35ml 油先腌鸡用 5ml，余下 30ml 加热；淋入姜葱、再淋鸡丝只是转移已备油。')
  bind(701, '鸡肉', [amount([9, 0], '鸡切块', ref('鸡肉')), reference([9, 3], '腌好的鸡肉')], [[7, 0], [9, 0], [9, 3]], '整批鸡肉先切块腌制，再铺到蒸盘，铺盘是同一批腌好的鸡肉，不重复投入。')
  bind(701, '虫草花', [{ at: [9, 2], target: '虫草花', kind: 'total' }, reference([9, 3], '虫草花')], [[7, 2], [9, 2], [9, 3]], '先铺泡好的虫草花，随后鸡肉铺在它上面，后一句的虫草花只是已铺好的底层，不追加一份。')
  bind(742, '料酒', [amount([10, 7], '20ml 料酒', part('料酒', 2, 3)), amount([10, 11], '10ml 料酒', part('料酒', 1, 3))], [[7, 8], [10, 7], [10, 11]], '每份料酒总 30ml 明示焯水 20ml、炖煮 10ml；不用人数范围决定配方份数。')
  bind(742, '姜', [amount([10, 7], '2 片姜', part('姜', 1, 3)), amount([10, 9], '**B包**', part('姜', 2, 3)), reference([10, 6], anchor(742, [10, 6]).text)], [[7, 2], [10, 6], [10, 7], [10, 9]], '每份 6 片姜：A包焯水 2 片、B包炖煮 4 片；分包仅备料，实际投入时各计一次。')
  bind(744, '黄酒或料酒', [amount([10, 3], '20ml 黄酒或料酒', part('黄酒或料酒', 4, 7), true), amount([10, 8], '15ml 料酒或黄酒', part('黄酒或料酒', 3, 7), true)], [[4, 8], [8, 8], [10, 3], [10, 8]], '每份两种酒二选一的合计 35ml：焯水 20ml、沿锅边 15ml；仍保留二选一条件，不分别算两种酒。')

  for (const [name, first, last, evidence] of [
    ['姜', '一半的葱姜', '葱姜料酒', 4],
    ['大葱', '一半的葱姜', '葱姜料酒', 2],
    ['料酒或者黄酒', '一半的料酒', '葱姜料酒', 5],
    ['冰糖', '一半的冰糖', '冰糖', 7],
  ] as const) {
    const optional = name === '料酒或者黄酒'
    bind(754, name, [amount([9, name === '冰糖' ? 3 : 2], first, part(name, 1, 2), optional), amount([9, 5], last, part(name, 1, 2), optional)], [[7, evidence], [9, 2], [9, 3], [9, 5]], '原文明示一半与其余一半。用同一总量的一半表达两阶段，范围按比例保留；不从前次范围擅选实际量推余量。')
  }

  for (const [name, firstTarget, secondAt, secondTarget, firstPart, total] of [
    ['生姜', '生姜泥', [17, 0], '生姜', 10, 20],
    ['大蒜', '大蒜泥', [17, 0], '大蒜', 4, 8],
    ['红辣椒粉', '红辣椒粉', [19, 2], '红辣椒粉 5g', 5, 10],
    ['盐', '盐', [19, 2], '盐 5g', 3, 8],
  ] as const) {
    bind(775, name, [amount([13, 0], firstTarget, part(name, firstPart, total)), amount([...secondAt], secondTarget, part(name, total - firstPart, total))], name === '盐' ? [[9, 6], [11, 14]] : name === '大蒜' ? [[9, 3], [11, 4]] : name === '生姜' ? [[9, 2], [11, 3]] : [[9, 4], [11, 7]], '按原文腌鸡/酱汁两张表分配；生姜 10+10g、大蒜 4+4瓣、红辣椒粉 5+5g、盐 3+5g，均是每配方份。')
  }
  bind(781, '姜', [amount([14, 1], '姜去皮后切丝，10g', part('姜', 10, 13)), amount([14, 6], '姜去皮后切丝 3g', part('姜', 3, 13)), reference([14, 4], '姜')], [[12, 3], [14, 1], [14, 6]], '原配方每 2 份姜 13g 分为微波前 10g、出炉后 3g；去除前一批姜不是再次投入。')
  bind(781, '青葱', [amount([14, 1], '葱白切丝 25g', part('青葱', 25, 35)), amount([14, 6], '葱绿切细丝 10g', part('青葱', 10, 35)), reference([14, 4], '青葱')], [[12, 1], [12, 2], [14, 1], [14, 6]], '每 2 份葱白 25g 与葱绿 10g 已分别明确；没有把整根葱任意对半。')
  bind(781, '料酒', [amount([14, 2], '每个袋子倒入 2.5mL 料酒', ref('料酒'))], [[12, 4], [14, 2]], '每袋仍为原文 2.5ml；本步提示为当前全部份数合计，不能把每袋数字改成整步总量。')

  quantity(799, '生姜', per(20, 'g'), [[8, 4], [10, 1], [10, 2]], '原文约量：每份约 20g，焯水与爆香各约 10g')
  quantity(799, '小葱', per(15, 'g'), [[8, 5]], '原文约量：每份约 15g；葱结与末尾可选葱末的分配未说明')
  bind(799, '生姜', [amount([10, 1], '大约 10g 姜片', part('生姜', 1, 2)), amount([10, 2], '剩余大约 10g 姜片', part('生姜', 1, 2))], [[8, 4], [10, 1], [10, 2]], '总量和两步都写约量；保留约字，各阶段按明确的 10/20 比例显示参考量，不升级为精确测量。')
  // 已知总量，未知葱结/葱末分配；明确阻断，不能用整份葱覆盖某一次。
  bind(799, '小葱', [{ at: [10, 1], target: '葱结', kind: 'unknown' }, { at: [10, 4], target: '小葱末', kind: 'unknown', conditional: true }], [[8, 5], [10, 1], [10, 4]], '原文每份约 15g，但前段葱结、末尾葱末没有分配比例，继续待确认。', false)

  for (const name of ['盐', '糖', '酱油', '风味调料']) {
    bind(810, name, [amount([10, 0], '1/2 所有上述调料', part(name, 1, 2), name === '风味调料'), amount([10, 1], '剩下 1/2 所有上述调料', part(name, 1, 2), name === '风味调料')], [[10, 0], [10, 1]], '原文明列油盐糖酱油风味调料各半，先腌肉、再入面糊；只分配明确调料，可选风味调料仍是条件量。额外煎油和水的口径不推定。')
  }
  quantity(731, '黑胡椒', per(4, 'g'), [[11, 0], [11, 6]], '原文腌制与炒制各 2g，每配方份合计 4g')
  bind(731, '黑胡椒', [amount([11, 0], '2g 黑胡椒', part('黑胡椒', 1, 2)), amount([11, 6], '2g 黑胡椒', part('黑胡椒', 1, 2))], [[11, 0], [11, 6]], '原文两次各 2g 黑胡椒均明确，合计 4g；半成品回锅不重复计入腌制量。')
  for (const name of ['生抽', '蚝油', '白糖', '胡椒粉', '盐']) {
    bind(750, name, [amount([10, 1], name, ref(name)), reference([10, 2], '加入准备好的碗汁')], [[8, name === '生抽' ? 4 : name === '蚝油' ? 7 : name === '白糖' ? 6 : name === '盐' ? 8 : 5]], '计算表明确该定量调味料专用于碗汁；调碗汁时计入一次，随后倒入同一碗汁不再扣一次。')
  }

  bind(708, '料酒', [{ ...amount([13, 0], '15ml 料酒', literal(15, 'ml')), display: false }, { at: [15, 3], target: '剩余的 10ml 料酒', kind: 'remaining' }], [[7, 4], [13, 0], [15, 3]], '标准 2–3 人固定批次料酒 25ml 已明确分 15ml 焯水、10ml 炒制；不把人数范围当缩放因子。')

  // 只补原文固定批次中的数字，不把约二人食用的一杯米样例擅定成每人份。
  for (const [name, value, conditional] of [['酱油', 30, false], ['耗油', 20, false], ['糖', 5, false], ['味淋', 40, true], ['料酒', 30, true], ['老抽', 5, true]] as const) {
    quantity(817, name, literal(value, 'g'), [[12, 4]], name === '耗油' ? '严格保留原文“耗油”字样，未自行更正名称' : conditional ? '味淋或料酒二选一；老抽可选；没有自动选择分支' : '以一杯米 160ml 为例的固定批次，不推定每人量', conditional)
    const target = name === '味淋' ? '40g `味淋`' : name === '料酒' ? '30g `料酒`' : `${value}g \`${name}\``
    bind(817, name, [amount([12, 4], target, ref(name), conditional), reference([14, 2], '`调料`')], [[12, 4], [14, 2]], '原句明确碗中调料重量，入锅是同一已备混合物。味淋/料酒和可选老抽保持条件，原文耗油与蚝油身份仍未擅改。')
  }
  return reviews
}
