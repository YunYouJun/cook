/** 明确执行才重新提取标注；导入和审查不会自动重签漂移的来源。 */
import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import type { AmountAnnotations, AmountEntry, AmountFormula, AmountQuantity } from './lib/recipe-amounts'
import fs from 'node:fs'
import { parsePreparationLine as parseLine, splitTopLevel } from './lib/recipe-amount-extraction'
import { applyReviewedAmountAllocations } from './lib/recipe-amount-followup'
import { add, anchorOf, canonicalUnit, count, divide, ingredientHeadings, key, literal, multiply, parseFormula, parseLiteral, scalar, servings, sourceFingerprint, sourceLines, stepHeadings } from './lib/recipe-amounts'
import { runAmountRuntime } from './run-amount-runtime'

const source: RecipeTextData = JSON.parse(fs.readFileSync('packages/data/recipe-text.json', 'utf8'))
const runtime = runAmountRuntime()
const result: AmountAnnotations = { schemaVersion: 1, recipes: {} }
const extraction: any[] = []
for (const [id, text] of Object.entries(source.recipes)) {
  const lines = sourceLines(text)
  const before = runtime[id].before.variants['3']
  const originalResolved = new Set(before.quantities.filter((q: any) => q.resolved).map((q: any) => key(q.name)))
  const entry: AmountEntry = { sourceFingerprint: sourceFingerprint(text), servingEvidence: [], quantities: [], stepSections: [], usages: [], blockedIngredients: [] }
  // 份数说明保留原句。只有“每份”标记，或完整的人数配方依据才用于数学缩放。
  const servingLines = lines.filter(line => line.section === '计算' && line.type !== 'list' && /份|人食用|人的份量|人份菜谱|一人量|一人份|一人食用/.test(line.text))
  entry.servingEvidence = servingLines.map(anchorOf)
  if (servingLines.length)
    entry.servingDescription = servingLines.map(line => line.text).join('；')
  entry.stepSections = lines.filter(line => line.type === 'heading' && stepHeadings.has(line.text) && !['操作', '操作步骤', '制作步骤', '步骤', '做法'].includes(line.text)).map(anchorOf)
  const candidates: { row: ReturnType<typeof parseLine>, line: typeof lines[number], basis?: typeof lines[number] }[] = []
  let basis: typeof lines[number] | undefined
  for (const line of lines) {
    if (line.type === 'heading') {
      basis = undefined
      continue
    }
    if (line.section !== '计算' && !ingredientHeadings.has(line.section))
      continue
    if (line.type === 'paragraph') {
      if (/(?:^|。)(?:每一?份|一份[：:量]|1 份[：:]|这是一份的量)|总量（按每份）|一份(?:正好|大约|约|可)?(?:够|适合)|以下为一份/.test(line.text))
        basis = line
      else if (/基础量|总量/.test(line.text))
        basis = undefined
    }
    if (line.type !== 'list' && !(line.type === 'paragraph' && line.section !== '计算'))
      continue
    // 所有子项逐条尝试；合并行只在各部分都是独立定量时拆分，锚点仍指向完整原项。
    const segments = line.text.split(/\n[•\s]*/).flatMap((part) => {
      const pieces = splitTopLevel(part)
      return pieces.length > 1 && pieces.every(piece => parseLine(piece)) ? pieces : [part]
    })
    for (const segment of segments) {
      const row = parseLine(segment)
      candidates.push({ row, line, ...(basis ? { basis } : {}) })
      extraction.push({ id: Number(id), anchor: anchorOf(line), segment, parsed: row ?? null })
    }
  }
  const names = new Map<string, string>()
  const knownUnits = new Map<string, string>()
  for (const { row } of candidates) {
    if (row) {
      names.set(key(row.name), row.name)
      const amount = parseLiteral(row.expression)
      if (amount?.op === 'literal')
        knownUnits.set(row.name, amount.unit)
      const unit = /[0-9一二三四五六七八九十两半]\s*(ml|mL|[g克个只根颗瓣片])/.exec(row.expression)?.[1]
      if (unit)
        knownUnits.set(row.name, canonicalUnit(unit))
    }
  }
  for (let index = candidates.length - 1; index >= 0; index--) {
    const candidate = candidates[index]!
    if (candidate.row && candidate.line.section !== '计算' && candidates.some(other => other.row?.name === candidate.row!.name && other.line.section === '计算'))
      candidates.splice(index, 1)
  }
  const duplicate = new Set(candidates.filter(({ row }) => row && candidates.filter(other => other.row?.name === row.name).length > 1).map(({ row }) => row!.name))
  for (const { row, line, basis } of candidates) {
    if (!row || duplicate.has(row.name) || originalResolved.has(key(row.name)))
      continue
    let formula = parseFormula(row.expression, names, knownUnits)
    if (!formula || ['count', 'scalar', 'servings'].includes(formula.op))
      continue
    // 没有公式的字面量只有明确“每份”作用域才乘份数，其他仍是原文固定批次。
    const hasServings = (value: AmountFormula): boolean => value.op === 'servings' || ('args' in value && value.args.some(hasServings))
    const evidence = [anchorOf(line)]
    if (!hasServings(formula) && basis && formula.op === 'literal') {
      formula = multiply(formula, servings)
      evidence.push(anchorOf(basis))
    }
    const quantity: AmountQuantity = { name: row.name, formula, evidence, aliases: [], ...(row.note ? { note: row.note } : {}), conditional: row.conditional || /^(?:进阶|可选)配料/.test(line.text) }
    // 原料清单里的完整文字作为别名，只允许精确同名的格式变体。
    for (const ingredient of lines.filter(value => value.section === '必备原料和工具' && value.type === 'list')) {
      if (key(ingredient.text.split(/[（(]/)[0]!.trim()) === key(row.name) || parseLine(ingredient.text)?.name === row.name) {
        if (/可选|可无|可省|不放|如需要|如需|低脂版本|酸甜口|咸鲜口/.test(ingredient.text)) {
          quantity.conditional = true
          quantity.note = [quantity.note, ingredient.text].filter(Boolean).join('；')
          quantity.evidence.push(anchorOf(ingredient))
        }
        if (ingredient.text !== row.name)
          quantity.aliases.push(ingredient.text)
      }
    }
    quantity.aliases = [...new Set(quantity.aliases)]
    entry.quantities.push(quantity)
  }
  result.recipes[id] = entry
}

// 逐菜核对后的补充；每条仍绑定完整原文，不从标题推定用量。
function setQuantity(id: number, name: string, formula: AmountFormula, snippets: string[], note?: string, conditional = false) {
  const lines = sourceLines(source.recipes[id]!)
  const evidence = snippets.map((snippet) => {
    const matches = lines.filter(line => line.text.includes(snippet))
    if (matches.length !== 1)
      throw new Error(`${id} ${name} evidence is not unique: ${snippet}`)
    return anchorOf(matches[0]!)
  })
  const entry = result.recipes[id]!
  entry.quantities = entry.quantities.filter(value => key(value.name) !== key(name))
  entry.quantities.push({ name, formula, evidence, aliases: [], ...(note ? { note } : {}), conditional })
}
// 青茄子在原文步骤中简称茄子；先算数量，酱油才向上取整。
setQuantity(225, '酱油', { op: 'ceil', args: [multiply(count('青茄子', '个'), literal(7, 'g'))] }, ['青茄子的数量 =', '酱油 = 茄子数量'], '单位沿用原文克')
// 完整、明确的固定项+每份项，不能简化成“全部乘份数”。
for (const [name, fixed, per, unit] of [['醋', 7.5, 4, 'ml'], ['酱油', 5, 2.5, 'ml'], ['盐', 0.4, 0.2, 'g'], ['香油', 5, 2, 'ml']] as const)
  setQuantity(668, name, add(literal(fixed, unit), multiply(literal(per, unit), servings)), [`${name} ${fixed}`])
// 两处公式明确写人均；其他行保持固定量，不能用“总量”推定每份。
setQuantity(821, '食用油', multiply(count('意大利面', 'g'), literal(0.1, 'ml')), ['意大利面 50 克 / 人', '食用油 5ml / 50 克意面'])
setQuantity(844, '盐', multiply(count('鸡蛋', '个'), literal(1, 'g')), ['鸡蛋的用量为 1 个', '盐的用量为 1 g 每个鸡蛋'])
setQuantity(844, '油', multiply(count('鸡蛋', '个'), literal(5, 'ml')), ['鸡蛋的用量为 1 个', '油的用量为 5 mL 每个鸡蛋'])
// “//1 向下取整”有明确取整依据；盐中“-”无法确定为范围还是减法，留待确认。
setQuantity(834, '鸡蛋', { op: 'floor', args: [multiply(literal(1.5, '个'), servings)] }, ['鸡蛋 （份数*1.5 // 1 向下取整）'])
// 705 原文明确定义量匙体积，只在这道菜内使用，不把体积换算成质量。
for (const q of result.recipes['705']!.quantities.filter(q => q.evidence.some(e => /茶匙|汤匙/.test(e.text)))) {
  const convert = (f: AmountFormula): AmountFormula => f.op === 'literal' && ['茶匙', '汤匙'].includes(f.unit)
    ? literal(f.lower * (f.unit === '茶匙' ? 5 : 15), 'ml', (f.upper ?? f.lower) * (f.unit === '茶匙' ? 5 : 15))
    : 'args' in f ? { ...f, args: f.args.map(convert) } : f
  q.formula = convert(q.formula)
  q.evidence.push(anchorOf(sourceLines(source.recipes['705']!).find(line => line.text === '1 汤匙 = 15ml')!), anchorOf(sourceLines(source.recipes['705']!).find(line => line.text === '1 茶匙 = 5ml')!))
  q.note = [q.note, '仅采用本菜谱明确给出的量匙体积，不换算成克'].filter(Boolean).join('；')
}

// 容易漏掉但原文能唯一确定的格式，逐句列证据，保持复合/备选材料的总量，不替其分配。
function exactQuantity(id: number, raw: string, name: string, amount: AmountFormula, perServing = false, conditional = false, note?: string) {
  const lines = sourceLines(source.recipes[id]!)
  const matches = lines.filter(l => l.text === raw && (l.section === '计算' || ingredientHeadings.has(l.section)))
  if (matches.length !== 1)
    throw new Error(`${id} exact quantity evidence changed: ${raw}`)
  const entry = result.recipes[id]!
  const evidence = [anchorOf(matches[0]!)]
  if (perServing) {
    const basis = lines.find(l => l.block < matches[0]!.block && l.type === 'paragraph' && /每份|每一份|每 2 份|一份|1 份/.test(l.text))
    if (!basis)
      throw new Error(`${id} missing serving evidence`)
    evidence.push(anchorOf(basis))
    amount = multiply(amount, servings)
  }
  entry.quantities = entry.quantities.filter(q => key(q.name) !== key(name))
  entry.quantities.push({ name, formula: amount, evidence, aliases: [], conditional, ...(note ? { note } : {}) })
}
for (const [raw, name, amount, unit] of [
  ['1 盒内脂豆腐', '内脂豆腐', 1, '盒'],
  ['20-30g 五花肉', '五花肉', 20, 'g'],
  ['2 片生姜', '生姜', 2, '片'],
  ['5 根小米辣', '小米辣', 5, '根'],
  ['20 颗花椒', '花椒', 20, '颗'],
] as const) exactQuantity(598, raw, name, literal(amount, unit, raw === '20-30g 五花肉' ? 30 : undefined))
exactQuantity(664, '陈醋 45ml（约 3 汤匙，醋与麻酱比例约 3:2）', '陈醋', literal(45, 'ml'), true, false, '原文每份约 3 汤匙，醋与麻酱比例约 3:2')
exactQuantity(702, '葱花（一根,约 20g）', '葱花', literal(1, '根'), true, false, '原文每份约 20g，部分葱白另用于葱姜末，不能重复备料')
exactQuantity(721, '土豆 2 个适中大小：750g', '土豆', literal(750, 'g'), false, false, '原文 2 个适中土豆，约 750g；固定 2 人版本')
exactQuantity(722, '生抽 75g = 15g * 5', '生抽', literal(75, 'g'), true)
exactQuantity(722, '蚝油 30g = 15g * 2', '蚝油', literal(30, 'g'), true)
exactQuantity(733, '盐 25g（注：若计划喝煮羊排的汤，则此处的盐在炖煮步骤中省略，在最后的煎烤步骤中加入）', '盐', literal(25, 'g'), true, true, '喝汤时炖煮阶段省略，改在煎烤阶段加入；不可两处扣量')
exactQuantity(743, '鹌鹑蛋（可选，没有鹌鹑蛋，可以用同等重量的鸡蛋代替）：0-2 个', '鹌鹑蛋', literal(0, '个', 2), true, true, '可选；同等重量替换需先明确鹌鹑蛋实际重量')
exactQuantity(768, '猪肉馅 250g（肥瘦比例 3:7）', '猪肉馅', literal(250, 'g'), true, false, '肥瘦比例 3:7，不是额外两份材料')
exactQuantity(772, '温水(30-40 ℃) 150ml', '温水', literal(150, 'ml'), true, false, '水温 30–40 ℃，温度不随份数缩放')
exactQuantity(773, '一斤鸡爪', '鸡爪', literal(1, '斤'))
exactQuantity(773, '八角三个', '八角', literal(3, '个'))
exactQuantity(785, '八角两个，干辣椒五个', '八角', literal(2, '个'))
exactQuantity(785, '八角两个，干辣椒五个', '干辣椒', literal(5, '个'))
exactQuantity(807, '葱、姜、蒜共 15 克', '葱、姜、蒜（合计）', literal(15, 'g'), false, false, '只记录合计量，各自比例未给出')
exactQuantity(810, '鸡蛋 （可选，1 个）', '鸡蛋', literal(1, '个'), true, true)
exactQuantity(823, '鸡蛋 （可选,2 个）', '鸡蛋', literal(2, '个'), false, true)
exactQuantity(830, '米粉（250g 记得 50 度的温水泡半小时）', '米粉', literal(250, 'g'), true, false, '原文每份 250g，50 度温水泡半小时；温度时间不缩放')
exactQuantity(834, '黄瓜（可选，份数*30g）', '黄瓜', multiply(literal(30, 'g'), servings), false, true)
exactQuantity(834, '胡萝卜（可选，份数*30g）', '胡萝卜', multiply(literal(30, 'g'), servings), false, true)
exactQuantity(845, '100°C 沸水 1500ml', '100°C 沸水', literal(1500, 'ml'), true, false, '100°C 是水温，不随份数缩放；按原文每份备水')
exactQuantity(845, '30°C 温水 1500ml', '30°C 温水', literal(1500, 'ml'), true, false, '30°C 是水温，不随份数缩放；按原文每份备水')
// 明确的互斥原料以整组数量保存，条件未选定前不参与余量扣减。
for (const [id, raw, name, amount, unit, perServing, upper] of [
  [699, '香油（或芝麻油） 2-4ml', '香油（或芝麻油）', 2, 'ml', false, 4],
  [699, '生抽 / 味极鲜 8ml', '生抽 / 味极鲜', 8, 'ml', false],
  [699, '白醋（或料酒） 2ml（可选）', '白醋（或料酒）', 2, 'ml', false],
  [713, '五花肉/瘦肉 200g', '五花肉/瘦肉', 200, 'g', true],
  [735, '猪排骨或者五花肉（总共） 1500 克', '猪排骨或者五花肉（合计）', 1500, 'g', false],
  [738, '酸奶或牛奶 5ml（可选）', '酸奶或牛奶', 5, 'ml', true],
  [739, '土豆或南瓜 150g', '土豆或南瓜', 150, 'g', true],
  [744, '黄酒或料酒 35ml', '黄酒或料酒', 35, 'ml', true],
  [754, '料酒或者黄酒 30ml-40ml', '料酒或者黄酒', 30, 'ml', true, 40],
  [791, '剑骨鱼或鲤鱼 1 条（约 750g，不去鳞）', '剑骨鱼或鲤鱼', 1, '条', true],
  [801, '昂刺鱼或者沙光鱼 一条', '昂刺鱼或者沙光鱼', 1, '条', true],
  [824, '肉丁/肉末 150g', '肉丁/肉末', 150, 'g', true],
  [833, '火腿肠或鸡胸肉 50g', '火腿肠或鸡胸肉', 50, 'g', true],
  [833, '玉米粒和青豆总共 30g', '玉米粒和青豆（合计）', 30, 'g', true],
  [841, '猪油（或鸡油）15 g', '猪油（或鸡油）', 15, 'g', true],
  [847, '温水或高汤 100-120ml（蛋液体积的 1.0-1.2 倍）', '温水或高汤', 100, 'ml', false, 120],
  [853, '全脂牛奶/奶油 10g', '全脂牛奶/奶油', 10, 'g', true],
  [856, '酱油（或生抽） 4 汤匙', '酱油（或生抽）', 4, '汤匙', true],
] as const) exactQuantity(id, raw, name, literal(amount, unit, upper), perServing, true, raw)
// 黄油鸡：同一份配方的腌鸡与酱汁明确分别列出，并非二选一，可加总，但各阶段不得重复扣总量。
for (const [name, amount, unit, raws] of [
  ['生姜', 20, 'g', ['生姜 10g，磨碎', '生姜 10g']],
  ['大蒜', 8, '瓣', ['大蒜 4 瓣（约 12g），磨碎', '大蒜 4 瓣']],
  ['红辣椒粉', 10, 'g', ['红辣椒粉 5g', '红辣椒粉 5g（Kashmiri 红辣椒粉最佳）']],
  ['盐', 8, 'g', ['盐 3g', '盐 5g']],
] as const) {
  const evidence = raws.map((raw) => {
    const line = sourceLines(source.recipes['775']!).find(l => l.section === '计算' && l.text === raw)
    if (!line)
      throw new Error(`775 phase evidence changed: ${raw}`)
    return anchorOf(line)
  })
  evidence.push(anchorOf(sourceLines(source.recipes['775']!).find(l => l.text === '每份：')!))
  result.recipes['775']!.quantities.push({ name, formula: multiply(literal(amount, unit), servings), evidence, aliases: [], conditional: false, note: '原文腌鸡与酱汁两部分合计，各阶段用量见原文' })
}

// 剩余的明确中文数量、同单位相加、合计量及有数字的备选方案。
for (const [id, raw, name, amount, unit, per, conditional, upper, note] of [
  [590, '芹菜 两根中等大小的芹菜', '芹菜', 2, '根', true, false, undefined, '原文中等大小'],
  [590, '大葱 10cm', '大葱', 10, 'cm', false, false, undefined, '按原文葱段长度保留，不缩放切段尺寸，不换算成克或根'],
  [601, '姜丝，以正常老姜切 2-3 片，然后片丝即可', '姜丝', 2, '片', false, false, 3, '先切片再片丝，数量单位沿用原文'],
  [601, '干辣椒（依照个人口味）2-3 个，切碎', '干辣椒', 2, '个', false, true, 3, '依照个人口味'],
  [601, '味精，看个人口味，不要放多，5g 即可。', '味精', 5, 'g', false, true, undefined, '原文口味建议，不推定必放'],
  [601, '蚝油，5g 即可，和味精一个道理', '蚝油', 5, 'g', false, true, undefined, '原文口味建议'],
  [609, '蔬菜（比如土豆片/豆芽/花菜/生菜/……） 可有不同搭配，推荐合计重量 300g 至 500g', '蔬菜（合计）', 300, 'g', false, true, 500, '不同蔬菜可搭配；只记录合计，不给每种分配'],
  [674, '淀粉（在锅里完全能盖住玉米粒表面为准，在 40 - 70g）', '淀粉', 40, 'g', false, true, 70, '以覆盖玉米粒表面为准'],
  [684, '老豆腐 1 块 （市场买 1.25 格 * 人）', '老豆腐', 1, '块', true, false, undefined, '沿用每份 1 块；市场格与块之间没有换算依据'],
  [707, '青红椒（根据受辣程度选择, 0-30g）*注：不建议使用肉厚的菜椒', '青红椒', 0, 'g', false, true, 30, '根据受辣程度选择，不建议用肉厚菜椒'],
  [721, '菜椒甜椒各一个，各 50g', '菜椒', 50, 'g', false, false, undefined, '原文此分项 1 个 50g'],
  [721, '菜椒甜椒各一个，各 50g', '甜椒', 50, 'g', false, false, undefined, '原文此分项 1 个 50g'],
  [727, '鸡蛋 1 个 50 g', '鸡蛋', 1, '个', true, false, undefined, '原文基准每个 50g'],
  [742, '猪五花肉：1000g（约 2 斤）。*注：生肉烹饪后缩水脱脂严重，此分量最终出肉量约满足 2-3 人正常饭量。如仅 1 人食用，建议所有食材剂量减半。*', '猪五花肉', 1000, 'g', true, true, undefined, '原文一份为 2–3 人量；1 人食用建议减半，不把人数当份数'],
  [743, '开水：没过食材的量，需要 600ml-900ml', '开水', 600, 'ml', false, true, 900, '以没过食材为准；不能仅按人数缩放水位'],
  [745, '青椒，甜椒 各 2 个', '青椒', 2, '个', false, false],
  [745, '青椒，甜椒 各 2 个', '甜椒', 2, '个', false, false],
  [747, '方法一：土豆淀粉 170g', '土豆淀粉（方法一）', 170, 'g', true, true, undefined, '仅在选用方法一时准备，不与方法二相加'],
  [747, '方法二：土豆淀粉 210g 和 中筋面粉 70g', '土豆淀粉（方法二）', 210, 'g', true, true, undefined, '仅在选用方法二时，与本方法面粉一起准备'],
  [747, '方法二：土豆淀粉 210g 和 中筋面粉 70g', '中筋面粉（方法二）', 70, 'g', true, true, undefined, '仅在选用方法二时，与本方法土豆淀粉一起准备'],
  [754, '水：没过食材的量，需要 1000ml', '水', 1000, 'ml', false, true, undefined, '原文参考量，以没过食材为准'],
  [758, '比拳头大一点的洋葱一个', '洋葱', 1, '个', false, false, undefined, '比拳头大一点的规格'],
  [787, '料酒、朗姆酒或啤酒 15g 到 30g', '料酒、朗姆酒或啤酒', 15, 'g', false, true, 30, '选用其中一种，不把质量换算成体积'],
  [787, '香菜 5 条 切段', '香菜', 5, '条', false, false],
  [787, '蒜 5 颗 剁成蒜蓉', '蒜', 5, '颗', false, false],
  [792, '翘嘴鱼：2 斤最佳', '翘嘴鱼', 2, '斤', true, true, undefined, '原文推荐规格；不擅自将斤转换成其他重量单位'],
  [792, '盐：约 50g 用于腌制鱼', '盐', 50, 'g', true, true, undefined, '原文约量，用于腌鱼，不作为确定已消耗量'],
  [802, '土豆淀粉 = 多少人的用量 * 40 克，本教程以一人用量算', '土豆淀粉', 40, 'g', true, false, undefined, '原文明确本教程按一人计算，40g/人'],
  [826, '蜂蜜 15 ml（没有时使用白糖 20 g + 玉米淀粉 5 g + 0.5 ml 柠檬汁或醋代替）', '蜂蜜', 15, 'ml', true, true, undefined, '蜂蜜与完整替代方案互斥；不自动选择'],
  [830, '剁椒（15g 辣椒剁完后, 个人需求适当放。 ）', '剁椒', 15, 'g', true, true, undefined, '辣椒剁完后按个人需求调整'],
  [830, '白糖（5g 如果不喜欢糖，可以考虑不放）', '白糖', 5, 'g', true, true, undefined, '不喜欢糖可以不放'],
  [839, '火锅牛肉卷 6 卷 60g', '火锅牛肉卷', 60, 'g', true, false, undefined, '原文一份 6 卷共 60g，不推广为任意肉卷密度'],
  [839, '豆芽 1 把 80g', '豆芽', 80, 'g', true, false, undefined, '本菜谱这一把为 80g，不推广为任意把数换算'],
  [860, '大蒜四五瓣、小米辣一两根', '大蒜', 4, '瓣', false, true, 5, '原文四五瓣，保持约数范围'],
  [860, '大蒜四五瓣、小米辣一两根', '小米辣', 1, '根', false, true, 2, '原文一两根，保持约数范围'],
] as const) exactQuantity(id, raw, name, literal(amount, unit, upper), per, conditional, note)
setQuantity(684, '鸡蛋', multiply(literal(2, '只'), count('老豆腐', '块')), ['鸡蛋 2 只 * 老豆腐块数', '老豆腐 1 块'], '以原文豆腐块数计算，不把格数当块数')
setQuantity(684, '玉米淀粉', multiply(literal(50, 'g'), count('老豆腐', '块')), ['玉米淀粉 50 g * 老豆腐块数', '老豆腐 1 块'], '酱汁另列 10g 与此总量的包含关系仍需确认')
setQuantity(704, '米酒', add(literal(10, 'ml'), literal(25, 'ml')), ['米酒 10 + 25 ml'], '原文同一单位相加；对应两个阶段，不推定每份')
// 已知河粉用量的默认与加量方案沿用原文；调料以实际选用河粉量为依据，不替用户选分支。
setQuantity(822, '老抽', multiply(count('河粉', 'g'), literal(10 / 250, 'ml')), ['老抽/生抽，分别为每 250g 河粉 10ml/15ml。'], '以原文河粉用量关系计算；加量方案需选择', true)
setQuantity(822, '生抽', multiply(count('河粉', 'g'), literal(15 / 250, 'ml')), ['老抽/生抽，分别为每 250g 河粉 10ml/15ml。'], '以原文河粉用量关系计算；加量方案需选择', true)
setQuantity(835, '挂面', multiply(literal(50, 'g', 100), servings), ['挂面 1 把（根据食量来）50-100g', '如下分量正好够 1 个人食用'], '原文一人量为一把 50–100g；只针对挂面，不推算鲜面条', true)
setQuantity(835, '西红柿', multiply(literal(1, '个'), servings), ['西红柿 1 个大概 200g 吧。', '如下分量正好够 1 个人食用'], '原文基准约 200g')
for (const [name, value] of [['蚝油', 5], ['鸡精', 3]] as const) setQuantity(835, `${name}（二选一）`, multiply(literal(value, 'g'), servings), ['蚝油 5g 或鸡精 3g', '如下分量正好够 1 个人食用'], '二选一，不同时计入投料', true)
// 主配方未记录黄油/米饭等量时仍保留缺少依据；不从他菜或视频标题补猜。

// 明确“每 2 份”的逗号格式；葱白/葱绿保留各自证据并加总，人数口径不另作推定。
for (const [raw, name, amount, unit] of [
  ['黑鳕鱼，带皮，2 片，450g（本菜谱主角，所有调料可根据鳕鱼的实际重量进行比例调整）', '黑鳕鱼', 450, 'g'],
  ['姜，13g。', '姜', 13, 'g'],
  ['料酒，5mL。', '料酒', 5, 'ml'],
  ['酱油，25mL。', '酱油', 25, 'ml'],
  ['芝麻油，2mL。', '芝麻油', 2, 'ml'],
  ['花生油，50mL。', '花生油', 50, 'ml'],
] as const) {
  exactQuantity(781, raw, name, divide(multiply(literal(amount, unit), servings), scalar(2)))
  result.recipes['781']!.quantities.find(q => q.name === name)!.evidence.push(anchorOf(sourceLines(source.recipes['781']!).find(l => l.text === '每 2 份：')!))
}
setQuantity(781, '青葱', divide(multiply(literal(35, 'g'), servings), scalar(2)), ['青葱，葱白，25g。', '青葱，葱绿，10g。', '每 2 份：'], '每 2 份葱白 25g、葱绿 10g，合计 35g')
result.recipes['781']!.referenceServings = 2
result.recipes['781']!.quantities.find(q => q.name === '黑鳕鱼')!.aliases.push('黑鳕鱼，带皮')

// 原文明示“按照 N 人份量”的菜，以每人一份换算，明确显示口径；不触碰人数范围与互相矛盾的 695。
for (const [id, expected, people] of [
  [264, '1 人食用的版本。', 1],
  [333, '按照 2 人的份量：', 2],
  [630, '按照 1 人的份量：', 1],
  [681, '按照两个人的份量：', 2],
  [699, '按照 1 人的份量：', 1],
  [779, '按照 1 人的份量：', 1],
  [805, '按照 1 人份的份量：', 1],
  [861, '注意，受食材个体影响，本菜谱为 2 人份菜谱', 2],
  [736, '1 人食用版本', 1],
  [756, '1 人食用版本', 1],
  [716, '本人饭量较大，这是本人一人量', 1],
] as const) {
  const basis = sourceLines(source.recipes[id]!).find(l => l.text === expected)
  if (!basis)
    throw new Error(`${id} person basis changed: ${expected}`)
  const entry = result.recipes[id]!
  for (const quantity of entry.quantities) {
    if (quantity.formula.op === 'literal') {
      quantity.formula = divide(multiply(quantity.formula, servings), scalar(people))
      quantity.evidence.push(anchorOf(basis))
    }
  }
  entry.referenceServings = people
  entry.servingEvidence = [anchorOf(basis)]
  entry.servingDescription = `每份按 1 人换算；原文：${expected}`
}
// 原文两次投盐公式齐全，可相加为整菜盐；油炸用油与后续克数没有密度依据，仍不相加。
setQuantity(225, '盐', add(divide({ op: 'reference', name: '面粉' }, scalar(20)), multiply(literal(3, 'g'), servings)), ['将鸡蛋打到盆中，加入（面粉 / 20）克的盐', '放入酱油和（份数 * 3）g 的盐'], '按原文两处明确投盐公式合计')
// 686 仅这一处给油的份数公式，按原名称“油”记录，不猜具体油品。
setQuantity(686, '油', multiply(literal(13, 'ml'), servings), ['开火，热锅，加入份数 * 13 毫升的油。'])
// 手工补充项也读取原备料清单里的可选说明和完整名称。
for (const [id, entry] of Object.entries(result.recipes)) {
  const prep = sourceLines(source.recipes[id]!).filter(l => l.section === '必备原料和工具' && l.type === 'list')
  for (const q of entry.quantities) {
    for (const line of prep) {
      if (key(line.text.split(/[（(]/)[0]!.trim()) !== key(q.name) && parseLine(line.text)?.name !== q.name)
        continue
      if (line.text !== q.name && !q.aliases.includes(line.text))
        q.aliases.push(line.text)
      if (/可选|可无|可省|不放|如需要|如需|低脂版本|酸甜口|咸鲜口/.test(line.text)) {
        q.conditional = true
        if (!q.evidence.some(a => a.block === line.block && a.item === line.item))
          q.evidence.push(anchorOf(line))
        if (!q.note?.includes(line.text))
          q.note = [q.note, line.text].filter(Boolean).join('；')
      }
    }
  }
}

// 把未确定的关系明确阻断，不能因为提取了总量就把它错误地灌入单一步骤。
for (const [id, entry] of Object.entries(result.recipes)) {
  const lines = sourceLines(source.recipes[id]!)
  const stepLines = lines.filter(line => stepHeadings.has(line.section) && line.type !== 'heading')
  const allNames = [...entry.quantities.map(q => q.name), ...runtime[id].before.variants['3'].quantities.map((q: any) => key(q.name))]
  const hasVariants = lines.some(line => line.type === 'heading' && /版本|fork|可控|不可控|使用海鲈鱼/.test(line.text))
  for (const name of new Set<string>(allNames)) {
    const mentions = stepLines.filter(line => line.text.includes(name))
    const alias = name.includes('油') ? '油' : name.includes('盐') ? '盐' : undefined
    const ambiguous = alias && stepLines.some(line => line.text.includes(alias) && !line.text.includes(name))
    const conditional = entry.quantities.find(q => q.name === name)?.conditional || lines.some(l => l.section === '必备原料和工具' && key(l.text.split(/[（(]/)[0]!.trim()) === key(name) && /可选|可省|不放|如果|若/.test(l.text))
    const repeats = mentions.length > 1
    // 范围/不定量/别名/多次引用：只有显式标注的完整投料账本才能解除阻断。
    if (hasVariants || ambiguous || conditional || repeats || mentions.some(line => /分次|分[两三二]次|剩余|剩下|一半|半[份量]|少许|适量/.test(line.text)))
      entry.blockedIngredients.push(name)
  }
}
// 番茄炒蛋的确定账本：保持既有盐余量验收；可选糖葱花只提示对应总量，不视为已消耗。
result.recipes['250']!.blockedIngredients = []
// 原文“分成两等份”的蒜末，数量与两处投料明确；葱白/葱绿不可按重量一分为二。
function use(id: number, ingredient: string, kind: 'amount' | 'total' | 'remaining' | 'unknown' | 'reference', snippet: string, target?: string, formula?: AmountFormula, conditional = false) {
  const lines = sourceLines(source.recipes[id]!)
  const match = lines.filter(line => stepHeadings.has(line.section) && line.type !== 'heading' && line.text.includes(snippet))
  if (match.length !== 1)
    throw new Error(`${id} usage not unique: ${snippet}`)
  const anchor = anchorOf(match[0]!)
  result.recipes[id]!.usages.push({ anchor, ingredient, kind, ...(target ? { target } : {}), ...(formula ? { formula } : {}), conditional, evidence: [anchor] })
}
use(259, '大蒜', 'amount', '下入一半的葱花', '一半的蒜末', divide(multiply(literal(4, '瓣'), servings), scalar(2)))
use(259, '大蒜', 'remaining', '倒入剩下的一半蒜末', '剩下的一半蒜末')
// 准备句只描述切配，不能再作为投料扣一遍；两等份依据追加到两个使用点。
for (const u of result.recipes['259']!.usages) u.evidence.push(anchorOf(sourceLines(source.recipes['259']!).find(line => line.text.includes('分为两等份备用'))!))
use(259, '大蒜', 'reference', '分为两等份备用', '大蒜')
// 同一投料句中的后续调料虽未重复写“加入”，仍有唯一的每份总量依据。
for (const [id, snippet, targets] of [
  [237, '加入 10 ml 生抽', [['生抽', '10 ml 生抽'], ['蚝油', '5 ml 蚝油'], ['白糖', '2 g 白糖']]],
  [259, '加 5ml 生抽', [['生抽', '5ml 生抽'], ['陈醋', '10ml 陈醋'], ['盐', '2g 盐']]],
] as const) {
  for (const [name, target] of targets) {
    use(id, name, 'amount', snippet, target, { op: 'reference', name })
    result.recipes[id]!.usages.at(-1)!.evidence.push(...result.recipes[id]!.quantities.find(q => q.name === name)!.evidence)
  }
}
// 红烧茄子步骤里的面粉依赖式，原文盐分两处，不能给其中一步整菜盐总量。
use(225, '盐', 'amount', '面粉 / 20', '（面粉 / 20）克', divide({ op: 'reference', name: '面粉' }, scalar(20)))
use(225, '盐', 'amount', '放入酱油和（份数 * 3）g', '（份数 * 3）g', multiply(literal(3, 'g'), servings))
// 原文明示每份三汤匙分别为腌制一汤匙、料汁两汤匙，保留汤匙单位。
use(731, '生抽', 'amount', '加入 1 汤匙生抽', '1 汤匙生抽', multiply(literal(1, '汤匙'), servings))
use(731, '生抽', 'amount', '调制料汁：', '2 汤匙生抽', multiply(literal(2, '汤匙'), servings))
for (const u of result.recipes['731']!.usages) u.evidence.push(anchorOf(sourceLines(source.recipes['731']!).find(l => l.text === '生抽 3 汤匙（1 汤匙用于腌制，2 汤匙用于料汁）')!))
// 708 是固定批次，姜与葱各阶段分配明确；准备/封装不是第二次消耗。
use(708, '姜', 'amount', '**冷水焯水：**', '15g 姜片', literal(15, 'g'))
use(708, '姜', 'amount', '**一键爆香：**', '【香料碟】', literal(15, 'g'))
use(708, '小葱', 'amount', '**冷水焯水：**', '15g 葱结', literal(15, 'g'))
use(708, '小葱', 'amount', '**点缀出锅：**', '10g 葱花', literal(10, 'g'))
for (const u of result.recipes['708']!.usages) u.evidence.push(anchorOf(sourceLines(source.recipes['708']!).find(l => l.section === '计算' && l.text.startsWith(u.ingredient === '姜' ? '姜 =' : '小葱 ='))!))

// 核对完整步骤后，明确的单次投料也可引用总量；后续成品/半成品指代不重复扣减。
for (const name of ['姜丝', '生抽', '蚝油', '盐', '食用油']) use(701, name, 'total', '鸡切块，加姜丝', name)
use(701, '鸡肉', 'amount', '鸡切块，加姜丝', '鸡切块', { op: 'reference', name: '鸡肉' })
use(701, '冬瓜', 'total', '冬瓜切薄片', '冬瓜')
use(701, '虫草花', 'total', '再铺一层泡好的虫草花', '虫草花')
use(789, '海虾', 'amount', '用刀从虾头中间切开', '虾头', { op: 'reference', name: '海虾' })
use(789, '蒜蓉酱', 'total', '将蒜蓉酱铺在虾身中间', '蒜蓉酱')
use(789, '食用油', 'total', '烧热油，倒入虾盘中', '热油')
use(789, '生抽', 'total', '烧热油，倒入虾盘中', '生抽')
use(853, '鸡蛋', 'total', '鸡蛋打入大碗中', '鸡蛋')
use(853, '盐', 'total', '鸡蛋打入大碗中', '盐')
use(853, '黄油', 'total', '黄油切小块入锅', '黄油')
use(853, '全脂牛奶/奶油', 'amount', '加入牛奶搅拌 15 秒', '牛奶', { op: 'reference', name: '全脂牛奶/奶油' }, true)
// 828 的酱油缩称和蚝油分支尚未确定，不能把默认参考量当作已采用方案。
use(828, '猪油', 'unknown', '碗底铺猪油')
use(828, '生抽', 'unknown', '淋上酱油与蚝油')
use(828, '老抽', 'unknown', '淋上酱油与蚝油')
use(828, '蚝油', 'unknown', '淋上酱油与蚝油', undefined, undefined, true)

// 所有能精确定位的步骤定量也形成账本。只记录原文固定数字，不将其自动当作每份量。
// 同一材料的其余潜在使用点显式记为 unknown，避免把不完整账本用于确定余量。
for (const [id, entry] of Object.entries(result.recipes)) {
  if (id === '250')
    continue // 已有经过验收的完整自动账本，保持同样的公式与余量路径。
  const lines = sourceLines(source.recipes[id]!).filter(l => stepHeadings.has(l.section) && l.type !== 'heading')
  const manualNames = new Set(entry.usages.map(u => u.ingredient))
  const names = [...new Set([...entry.quantities.map(q => q.name), ...runtime[id].before.variants['3'].quantities.map((q: any) => key(q.name))])] as string[]
  for (const name of names) {
    if (manualNames.has(name) || /[()（）/、+]/.test(name))
      continue
    const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const unit = '(?:千克|公斤|毫升|汤匙|茶匙|kg|KG|ml|mL|ML|g|G|L|l|克|斤|升|个|颗|只|根|瓣|片|朵|张|支|块|勺|把|杯|袋|包|盒|瓶|罐|枚|粒|棵|条|碗|滴|扎|圈|节|段|株|叶|卷|头|份)'
    const atom = '(?:[0-9]+(?:\\.[0-9]+)?|[一二三四五六七八九十两半])'
    const amount = `(?:${atom}(?:\\s*${unit})?\\s*[-–~～至到]\\s*${atom}\\s*${unit}|${atom}(?:\\s*/\\s*${atom})?\\s*${unit})(?![a-zA-Z])`
    const pattern = new RegExp(`(${amount})\\s*(?:的)?\\s*${escaped}|${escaped}\\s*(${amount})`, 'g')
    const uses: typeof entry.usages = []
    for (const line of lines) {
      if (!line.text.includes(name))
        continue
      let found = false
      for (const match of line.text.matchAll(pattern)) {
        const namePosition = match.index! + match[0].indexOf(name)
        if (names.some(other => other.length > name.length && other.includes(name) && [...line.text.matchAll(new RegExp(other.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'))].some(m => m.index! <= namePosition && m.index! + other.length >= namePosition + name.length)))
          continue
        const target = match[1] ?? match[2]!
        const formula = parseLiteral(target)
        if (!formula || line.text.split(match[0]).length !== 2)
          continue
        const before = line.text.slice(0, match.index).split(/[，,。；;\n]/).at(-1) ?? ''
        if (!/加入|放入|倒入|撒入|撒上|淋入|下入|[加放倒撒淋用取]|打入/.test(before) || /不要|不能|不必|无需|勿/.test(before))
          continue
        // 不把长公式的一部分当成固定数字。
        const around = line.text.slice(Math.max(0, match.index! - 8), match.index! + match[0].length + 8)
        if (/份数|[*×/]/.test(around.replace(target, '')))
          continue
        const anchor = anchorOf(line)
        uses.push({ anchor, ingredient: name, kind: 'amount', formula, target: match[0], display: false, conditional: conditionFor(line.text) || /版本|fork|可控|不可控/.test(line.section), evidence: [anchor] })
        found = true
      }
      if (!found && /[加放倒撒淋腌拌用取]|打入|下锅/.test(line.text)) {
        const anchor = anchorOf(line)
        uses.push({ anchor, ingredient: name, kind: 'unknown', conditional: true, evidence: [anchor] })
      }
    }
    if (uses.some(u => u.kind === 'amount'))
      entry.usages.push(...uses)
  }
}
function conditionFor(text: string) {
  return /可选|如果|若|否则|可以|可无|可省|不放|按需|或者|根据|适量|少许/.test(text)
}

// 唯一、无条件的单次投料，与明确的每份总量完全相等时，可以同步换算。
// 不推广到分次用量、别名、分支、未知使用点或不相等的数字。
for (const entry of Object.values(result.recipes)) {
  for (const quantity of entry.quantities) {
    const formula = quantity.formula
    if (quantity.conditional || entry.blockedIngredients.includes(quantity.name)
      || formula.op !== 'multiply' || formula.args[0]?.op !== 'literal' || formula.args[1]?.op !== 'servings') {
      continue
    }
    const uses = entry.usages.filter(u => u.ingredient === quantity.name)
    const usage = uses[0]
    if (uses.length !== 1 || !usage || usage.kind !== 'amount' || usage.conditional || usage.display !== false
      || JSON.stringify(usage.formula) !== JSON.stringify(formula.args[0])) {
      continue
    }
    usage.formula = { op: 'reference', name: quantity.name }
    usage.display = true
    usage.evidence.push(...quantity.evidence)
  }
}

const reviewedRelations = applyReviewedAmountAllocations(source, result)
fs.writeFileSync('packages/data/recipe-amounts.json', `${JSON.stringify(result, null, 2)}\n`)
fs.mkdirSync('artifacts/recipe-amount-audit', { recursive: true })
fs.writeFileSync('artifacts/recipe-amount-audit/extraction.json', `${JSON.stringify(extraction, null, 2)}\n`)
fs.writeFileSync('artifacts/recipe-amount-audit/followup-relations.json', `${JSON.stringify(reviewedRelations, null, 2)}\n`)
console.log(`标注 ${Object.keys(result.recipes).length} 道正文，${Object.values(result.recipes).reduce((n, entry) => n + entry.quantities.length, 0)} 条补充用量。`)
