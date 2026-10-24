import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import type { AmountAnchor, AmountAnnotations } from './lib/recipe-amounts'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { parsePreparationLine } from './lib/recipe-amount-extraction'
import { applyReviewedAmountAllocations } from './lib/recipe-amount-followup'
import { recipeAmountReviewNotes } from './lib/recipe-amount-review-notes'
import { anchorOf, ingredientHeadings, isContainedIngredientMention, key, parseLiteral, sourceLines, stepHeadings, units } from './lib/recipe-amounts'
import { runAmountRuntime } from './run-amount-runtime'

const directory = 'artifacts/recipe-amount-audit'
const read = (file: string) => JSON.parse(fs.readFileSync(`packages/data/${file}`, 'utf8'))
const catalogCSV = fs.readFileSync('packages/data/recipe.csv', 'utf8')
const recipes = parseRecipeCsv(catalogCSV)
const oneRecordPerLine = catalogCSV.trimEnd().split('\n').length === recipes.length + 1
const generated = read('recipe.json')
const texts: RecipeTextData = read('recipe-text.json')
const annotations: AmountAnnotations = read('recipe-amounts.json')
const checkedAnnotations = structuredClone(annotations)
const reviewedRelations = applyReviewedAmountAllocations(texts, checkedAnnotations)
if (JSON.stringify(checkedAnnotations) !== JSON.stringify(annotations))
  throw new Error('第二轮已复核关系尚未写入标注，请显式运行 amounts:annotate 并审阅差异')
const sortedIds = (list: { id?: number }[]) => list.map(r => r.id).sort((a, b) => a! - b!)
if (JSON.stringify(sortedIds(recipes)) !== JSON.stringify(sortedIds(generated)))
  throw new Error('CSV 与应用目录 ID 集合不一致，先运行 convert')
if (new Set(recipes.map(r => r.id)).size !== recipes.length)
  throw new Error('目录 ID 重复')
const runtime = runAmountRuntime()
fs.mkdirSync(directory, { recursive: true })
const categories = {
  no_body: '无内置正文依据',
  prep_calculation_scope: '备料清单与计算表口径待确认',
  missing_serving_basis: '缺少确定份数/缩放依据',
  missing_quantity: '备料缺少可读取数量',
  unparsed_quantity: '备料或计算语句未完整结构化',
  unknown_formula: '未知或不完整公式',
  unresolved_step_usage: '应用尚未完整解析的投料关系',
  unlinked_step_quantity: '步骤定量尚未完整关联材料',
  incompatible_units: '单位/维度不兼容',
  range: '用量范围',
  rounded_dependency: '取整或原料依赖',
  alias_or_reference: '别名/加工后指代或合并材料',
  repeated_addition: '多次引用或分次投料',
  remaining: '余量/剩余关系',
  optional_condition: '条件/可选/互斥分支',
  overuse: '投料超量或原文不一致',
  step_scaling_basis_missing: '步骤固定数字与备料缩放关系未说明',
  unrecognized_section: '非标准章节或仅作备注',
}
type Category = keyof typeof categories
interface Finding {
  categories: Category[]
  status: 'resolved' | 'preserved_fixed' | 'conditional' | 'needs_confirmation'
  determinable: boolean
  location: { section: string, block?: number, item?: number, step?: number }
  quote: string
  reason: string
  confirmation: string
}
const measurements = /(?:\d+(?:\.\d+)?|[一二三四五六七八九十两半])(?:\s*[\-–~～至到/]\s*(?:\d+(?:\.\d+)?|[一二三四五六七八九十两半]))?\s*(?:千克|公斤|毫升|汤匙|茶匙|kg|KG|ml|mL|ML|[gGL克斤升个颗只根瓣片朵张支块勺把杯袋包盒瓶罐枚粒棵条碗滴扎圈节段株叶卷头份])(?![a-zA-Z])/g
const condition = /可选|如果|若|否则|可以|可无|可省|不放|按需|或者|二选一|选其一|方法[一二]|版本|fork|根据.*(?:情况|口味|锅)|实际消耗|没过|覆盖锅底/
const range = /(?:\d+(?:\.\d+)?|[一二三四五六七八九十两半])\s*(?:(?:g|ml|克|毫升)\s*)?[-–~～至到]\s*(?:\d+(?:\.\d+)?|[一二三四五六七八九十两半])/
const usageVerb = /[加放倒撒淋腌拌用取煎炸煮蒸]|打入|下锅/
const tools = /^(?:[锅刀碗]|炒锅|平底锅|不粘锅|汤锅|砂锅|蒸锅|锅铲|菜刀|砧板|案板|筷子|勺子|烤箱|空气炸锅|微波炉|电饭煲|高压锅|打蛋器|烤盘|冰箱|定时器|秒表|漏勺|厨房温度计|厨房秤|量杯|吸油纸|蒸笼|电压力锅|密封袋|保鲜膜|耐热碗|料理机|搅拌机)(?:[（(]|$)/
const audit = recipes.map((recipe, catalogIndex) => {
  const id = recipe.id!
  const source = texts.recipes[id]
  const findings: Finding[] = []
  const entry = annotations.recipes[id]
  const actual = runtime[id]
  const add = (cats: Category[], anchor: AmountAnchor | undefined, status: Finding['status'], reason: string, confirmation = '', section?: string) => {
    const sourceLine = anchor && source ? sourceLines(source).find(l => l.block === anchor.block && l.item === anchor.item) : undefined
    const steps = actual?.after.variants['3'].steps ?? []
    findings.push({ categories: cats, status, determinable: status !== 'needs_confirmation', location: { section: section ?? sourceLine?.section ?? '目录', ...(anchor ? { block: anchor.block, ...(anchor.item === undefined ? {} : { item: anchor.item }) } : {}), ...(anchor && steps.some((s: any) => s.sourceBlock === anchor.block && (s.sourceItem ?? undefined) === anchor.item) ? { step: steps.find((s: any) => s.sourceBlock === anchor.block && (s.sourceItem ?? undefined) === anchor.item).number } : {}) }, quote: anchor?.text ?? `${recipe.name}（目录只提供食材：${recipe.stuff.join('、')}）`, reason, confirmation })
  }
  if (!source) {
    add(['no_body'], undefined, 'needs_confirmation', '目录没有离线用量或步骤正文；视频/链接未转录，不能从食材名称推定配方。', '提供与该 ID 对应的原始材料数量、明确份数基准和步骤分次投料记录。')
    return { id, name: recipe.name, source: { file: 'packages/data/recipe.csv', ...(oneRecordPerLine ? { line: catalogIndex + 2 } : {}), video: recipe.bv ?? null, url: recipe.link ?? null }, hasBody: false, findings }
  }
  if (!actual || !actual.after.annotationLoaded)
    throw new Error(`${id} 标注没有被应用实际读取或来源校验失败`)
  const lines = sourceLines(source)
  for (const note of recipeAmountReviewNotes.filter(note => note.id === id)) {
    for (const quote of note.quotes) {
      const matches = lines.filter(line => line.text === quote)
      if (matches.length !== 1)
        throw new Error(`${id} 人工核对的来源原句变化，需重新审查：${quote}`)
      add([...note.categories] as Category[], anchorOf(matches[0]!), 'needs_confirmation', note.reason, note.confirmation)
    }
  }
  const variants = actual.after.variants
  const before = actual.before.variants['3']
  const after = variants['3']
  const relations = reviewedRelations.filter(relation => relation.id === id)
  const quantityFor = (name: string) => after.quantities.find((q: any) => key(q.name) === key(name) || entry?.quantities.find(a => a.name === q.name)?.aliases.includes(name))
  const usedEvidence = new Set((entry?.quantities ?? []).flatMap(q => q.evidence.map(e => `${e.block}:${e.item ?? ''}`)))
  const legacyEvidence = new Set(before.quantities.map((q: any) => q.source))
  const servingEvidence = [...(entry?.servingEvidence ?? []), ...lines.filter(l => /份数|\/\s*(?:(?:[一二三四五六七八九十两]|\d+)\s*)?人|人份|一人量|一人份|人食用|人的份量/.test(l.text))]
  if (!servingEvidence.length)
    add(['missing_serving_basis'], anchorOf(lines.find(line => line.section === '计算' && line.type !== 'heading') ?? lines.find(line => ingredientHeadings.has(line.section) && line.type === 'list') ?? lines[0]!), 'needs_confirmation', '正文没有明确份数、人数或每份/人均关系；已知固定量保留，不能自动随份数缩放。', '确认原配方对应多少份/人，以及哪些固定量允许随份数改变。', '计算')
  for (const q of entry?.quantities ?? []) {
    add(q.formula.op === 'ceil' || q.formula.op === 'floor' || JSON.stringify(q.formula).includes('reference') || JSON.stringify(q.formula).includes('count') ? ['rounded_dependency'] : [], q.evidence[0], q.conditional ? 'conditional' : 'resolved', `已接入应用：${q.name}；3 份显示 ${quantityFor(q.name)?.text ?? '未解析'}。${q.conditional ? '只在采用原文条件时适用，不参与无条件余量扣减。' : ''}`, q.conditional ? '按原文选择分支；如要自动扣余量，需明确本次采用哪个分支。' : '')
  }
  for (const q of after.quantities) {
    if (!q.resolved || !q.hasKnownUnit) {
      const line = lines.find(l => l.text.includes(q.source))
      add(['unknown_formula'], line && anchorOf(line), 'needs_confirmation', `当前求值器不能完整计算「${q.name}」：${q.source}。`, '确认公式变量、计量单位、依赖材料、范围/运算符或取整规则的确切含义。')
    }
    else if (!q.scalable) {
      const line = lines.find(l => l.text.includes(q.source)) ?? lines.find(l => l.text.includes(q.name) && l.section === '计算')
      add([], line && anchorOf(line), 'preserved_fixed', `「${q.name}」保留原文固定量 ${q.text}；不将其推定成每份量。`)
    }
  }
  const preparation = lines.filter(l => ingredientHeadings.has(l.section) && l.type === 'list')
  const extraPrep = preparation.filter(l => l.section !== '必备原料和工具' && !usedEvidence.has(`${l.block}:${l.item ?? ''}`))
  for (const line of extraPrep) {
    if (tools.test(line.text) || quantityFor(line.text)?.resolved)
      continue
    add(['missing_quantity'], anchorOf(line), 'needs_confirmation', '这个非标准原料章节中的材料/说明还没有唯一可求值的备料量；没有从菜名或其他配方推定。', '确认材料对应的总量、单位与适用配方；若本句实际是预处理步骤，请明确其投料身份。')
  }
  for (const line of preparation.filter(l => l.section === '必备原料和工具')) {
    const parsed = parsePreparationLine(line.text)
    const raw = parsed && parseLiteral(parsed.expression)
    const quantity = quantityFor(line.text) ?? (parsed && quantityFor(parsed.name))
    if (raw?.op !== 'literal' || !quantity)
      continue
    const reference = variants[String(entry?.referenceServings ?? 1)].quantities.find((q: any) => q.name === quantity.name)?.numericMeasurement
    if (!reference?.unit)
      continue
    const unitBase = (unit: string) => unit === 'kg' ? ['g', 1000] as const : unit === 'L' ? ['ml', 1000] as const : [unit, 1] as const
    const [rawUnit, rawFactor] = unitBase(raw.unit)
    const [calculationUnit, calculationFactor] = unitBase(reference.unit)
    if (rawUnit !== calculationUnit || Math.abs(raw.lower * rawFactor - reference.lower * calculationFactor) > 1e-9 || Math.abs((raw.upper ?? raw.lower) * rawFactor - reference.upper * calculationFactor) > 1e-9) {
      add(['prep_calculation_scope'], anchorOf(line), 'needs_confirmation', `备料清单写「${line.text}」，计算表的原配方量为 ${reference.lower}${reference.upper === reference.lower ? '' : `–${reference.upper}`} ${reference.unit}；可能分别指采购、包装规格或实际使用量，未无依据互换。应用的计算提示依据计算表，原备料文字完整保留。`, '确认清单数值是采购/包装量还是实际投料量；不同单位时给出本菜谱规格换算，或明确以计算表为使用量依据。')
    }
  }
  for (const ingredient of actual.after.ingredients) {
    if (tools.test(ingredient))
      continue
    const quantity = quantityFor(ingredient)
    if ((!quantity?.resolved || !quantity.hasKnownUnit)) {
      const line = lines.find(l => l.section === '必备原料和工具' && l.text === ingredient)
      add(['missing_quantity', ...(/[、，,/]|或者|[或（(]/.test(ingredient) ? ['alias_or_reference' as Category] : [])], line && anchorOf(line), 'needs_confirmation', `备料「${ingredient}」未能精确关联到唯一、有效的数量；不靠子串合并调料或复合食材。`, `确认「${ingredient}」对应哪个计算材料、准确总量/单位，或说明这是无需定量的工具/装饰。`)
    }
  }
  const sourceUnits = new Map<string, Set<string>>()
  const additionLines = lines.filter(l => stepHeadings.has(l.section) && l.type !== 'heading')
  for (const line of lines) {
    if (line.type === 'heading') {
      if (!['计算', '附加内容', '参考资料', '备注说明', '成品', '营养成分'].includes(line.text) && !stepHeadings.has(line.text) && !ingredientHeadings.has(line.text))
        add(['unrecognized_section'], anchorOf(line), 'needs_confirmation', '非标准章节保留原文，没有把其中内容自动当作投料步骤。', '确认该章节是操作、备料还是参考说明。')
      continue
    }
    const isStep = stepHeadings.has(line.section)
    const isCalculation = line.section === '计算' || ingredientHeadings.has(line.section)
    if (!isStep && !isCalculation && !['附加内容', '简介', '参考资料', '成品', '营养成分', '备注说明'].includes(line.section)) {
      add(['unrecognized_section'], anchorOf(line), 'needs_confirmation', '该非标准章节的原文尚未归入可求值备料或步骤结构，保持原文。', '确认本句是否包含投料关系，以及材料、用量、份数依据和适用条件。')
      continue
    }
    if (!isStep && !isCalculation && line.section !== '附加内容')
      continue
    const measured = [...line.text.matchAll(measurements)]
    const tags: Category[] = []
    if (measured.some(m => range.test(m[0])) || new RegExp(`(?:\\d+(?:\\.\\d+)?)\\s*(?:${units})\\s*[-–~～至到]\\s*(?:\\d+(?:\\.\\d+)?)\\s*(?:${units})(?![A-Za-z])`).test(line.text))
      tags.push('range')
    if (/向[上下]取整|(?:数量|斤数|面粉|蛋液体积)\s*[*×/]/.test(line.text))
      tags.push('rounded_dependency')
    if (condition.test(line.text))
      tags.push('optional_condition')
    if (/剩余|剩下|余下/.test(line.text))
      tags.push('remaining')
    if (/分[两二三四]次|分次|分[成为].*份|一半|各一半/.test(line.text))
      tags.push('repeated_addition')
    if (/即为|得到.*[液汁糊]|[、/].*(?:共|合计|总共)|姜蒜|葱姜蒜|调料碗|酱汁|面糊/.test(line.text))
      tags.push('alias_or_reference')
    const step = after.steps.find((s: any) => s.sourceBlock === line.block && (s.sourceItem ?? undefined) === line.item)
    const annotated = usedEvidence.has(`${line.block}:${line.item ?? ''}`) || legacyEvidence.has(line.text.split(/[=＝]/).slice(1).join('=').trim())
    if ((line.section === '计算' || (isCalculation && measured.length > 0 && line.section !== '必备原料和工具')) && line.type === 'list' && !annotated && !/^1\s*[汤茶]匙\s*=/.test(line.text)) {
      add(['unparsed_quantity', ...tags], anchorOf(line), 'needs_confirmation', '本条备料/计算原文未被完整转换为可读取数量；可能含复合材料、不同方法、未知单位或缺数值。', '确认本条每种材料的总量、单位、份数作用域和适用分支；复合总量需说明是否可分配。')
    }
    else if (tags.length) {
      const remainingResolved = tags.includes('remaining') && step?.adjusted && !step.unresolved && /(?:剩余|剩下|余下)[^，。]{0,15}[（(]\d/.test(step.text) && !/(?:剩余|剩下|余下)[^，。]{0,15}[（(]\d/.test(step.source)
      const checkedRelation = isStep && !step?.unresolved && relations.some(r => r.complete && r.usages.some(u => u.anchor.block === line.block && u.anchor.item === line.item))
        && tags.every(t => ['remaining', 'repeated_addition', 'range', 'rounded_dependency'].includes(t))
      const status = remainingResolved || checkedRelation || (annotated && !tags.some(t => ['optional_condition', 'alias_or_reference', 'repeated_addition'].includes(t))) ? 'resolved' : tags.every(t => t === 'optional_condition') ? 'conditional' : 'needs_confirmation'
      add(tags, anchorOf(line), status, remainingResolved ? '应用按原文顺序计算了明确余量，步骤勾选不影响结果。' : checkedRelation ? '逐篇复核已确定该分配，生产步骤无未解析关系；比例范围保留，不从前次范围选择实际用量。' : status === 'resolved' ? '明确范围或依赖保留并参与应用求值。' : '原文含条件、范围、加工后指代、分次或剩余关系；尚不能建立唯一且无条件的完整投料账本。', status === 'needs_confirmation' ? '确认本句指向的材料、各次用量/比例、单位及采用条件；前序为范围时需确定实际投了多少。' : status === 'conditional' ? '按原文选择分支；未选择时不作为已消耗量。' : '')
    }
    if (isStep && step?.unresolved && /加入|放入|倒入|撒入|撒上|淋入|调入|加(?![热温速])|[铺撒淋腌拌]/.test(line.text))
      add(['unresolved_step_usage'], anchorOf(line), 'needs_confirmation', '生产 CookCore 对本步仍返回需确认：可能有分次、别名/半成品、条件或不定量，无法证明完整投料关系。已明确的材料数量保持可用，未确定部分不补猜。', '核对本步是投入新原料、使用已备混合物还是回锅/移容器；明确原料对象、每次数量/比例与适用条件。')
    if (isStep && measured.length) {
      const targets = entry?.usages.filter(u => u.anchor.text === line.text && u.kind === 'amount').map(u => u.target!) ?? []
      const unlinked = measured.filter(m => !targets.some(target => target.includes(m[0])))
      if (unlinked.length && !(line.text.includes('份数') && step?.adjusted && !step.unresolved))
        add(['unlinked_step_quantity'], anchorOf(line), 'needs_confirmation', `本步定量片段 ${[...new Set(unlinked.map(m => m[0]))].join('、')} 尚未完整关联到唯一的材料投料事件；也可能表示切块、预处理或容器量。`, '确认各数字对应的材料、用途、是否首次投料/回锅，以及是否随份数改变。')
    }
    if (isStep && measured.length && !/份数/.test(line.text)) {
      const relevant = after.quantities.filter((q: any) => q.scalable && line.text.includes(key(q.name)))
      if (relevant.length && !entry?.usages.some(u => u.anchor.text === line.text && u.kind === 'amount' && /servings|reference|count/.test(JSON.stringify(u.formula))))
        add(['step_scaling_basis_missing'], anchorOf(line), 'needs_confirmation', `本步保留固定数字，而备料 ${relevant.map((q: any) => q.name).join('、')} 会随份数变化；不能自动认定本步每个数字都随份数缩放。`, '确认这些步骤数字是整批固定量、每份量、切块尺寸还是某一分支用量，并给出与总备料的关系。')
    }
    // 精确材料名附近的测量单位用于发现维度冲突；候选只作待确认，绝不自动克/毫升互转。
    if (isStep && usageVerb.test(line.text)) {
      for (const q of after.quantities) {
        const name = key(q.name)
        if (!line.text.includes(name))
          continue
        const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
        const nearby = new RegExp(`(?:([0-9]+(?:\\.[0-9]+)?)\\s*(茶匙|汤匙|kg|g|克|千克|ml|mL|毫升|L|升)\\s*(?:的)?\\s*${escaped}|${escaped}\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(茶匙|汤匙|kg|g|克|千克|ml|mL|毫升|L|升))`, 'g')
        const values = [...line.text.matchAll(nearby)]
        const totalUnit = q.numericMeasurement.unit
        const base = (unit: string) => /^(?:g|克|kg|千克)$/.test(unit) ? 'mass' : /^(?:ml|mL|毫升|L|升)$/.test(unit) ? 'volume' : unit
        for (const value of values) {
          if (isContainedIngredientMention(line.text, name, value.index! + value[0].indexOf(name), after.quantities.map((q: any) => key(q.name))))
            continue
          const unit = value[2] ?? value[4]!
          sourceUnits.set(name, (sourceUnits.get(name) ?? new Set()).add(unit))
          if (totalUnit && base(totalUnit) !== base(unit))
            add(['incompatible_units'], anchorOf(line), 'needs_confirmation', `「${name}」备料为 ${totalUnit}，本步为 ${unit}；缺少该材料的换算依据。`, '提供材料特定的密度/量匙标准，或用与总量一致的单位明确本步数量。')
        }
      }
    }
  }
  // 用生产求值器给出的步骤定量筛出“累加超过总量”的候选，不把回锅或重复叙述直接判为实际超量。
  const one = variants[String(entry?.referenceServings ?? 1)]
  for (const q of one.quantities) {
    const total = q.measurement
    if (!total.unit)
      continue
    const name = key(q.name)
    const explicit = one.usageMeasurements.filter((u: any) => key(u.ingredient) === name && !u.conditional && u.lower === u.upper)
    const base = (unit: string) => unit === 'kg' ? ['g', 1000] as const : unit === 'L' ? ['ml', 1000] as const : [unit, 1] as const
    const [unit, factor] = base(total.unit)
    const compatible = explicit.filter((u: any) => base(u.unit)[0] === unit)
    const used = compatible.reduce((sum: number, u: any) => sum + u.lower * base(u.unit)[1] / factor, 0)
    if (used > total.upper + 1e-9) {
      const evidence = lines.find(l => l.text === compatible[0].source)
      add(['overuse', 'repeated_addition'], evidence && anchorOf(evidence), 'needs_confirmation', `「${name}」按原配方 ${entry?.referenceServings ?? 1} 份的步骤候选定量合计 ${used} ${total.unit}，超过原备料上限 ${total.upper} ${total.unit}；可能是追加、回锅或重复叙述，不能当作确定账本。`, '核对各次是否为独立追加，确认备料总量是否包含所有阶段；给出准确分配或修正来源。')
    }
  }
  for (const q of after.quantities) {
    const name = key(q.name)
    const mentions = additionLines.filter(l => l.text.includes(name) && usageVerb.test(l.text))
    if (mentions.length > 1) {
      const relation = relations.find(r => r.ingredient === name && r.complete)
      add(['repeated_addition'], anchorOf(mentions[0]!), relation ? q.conditional ? 'conditional' : 'resolved' : 'needs_confirmation', relation ? `「${name}」分配已逐篇复核并写入生产标注：${relation.explanation}` : `「${name}」在 ${mentions.length} 条操作中被使用或引用；包含预处理、回锅、混合物时不能重复扣总量。`, relation ? q.conditional ? '仍需使用者选择原文分支；未选择时不参与无条件余量。' : '' : `核对「${name}」各处是首次投入、再加工、回锅还是追加，并给出追加量/比例。`)
    }
  }
  return { id, name: recipe.name, source: { file: `packages/data/recipe-text.json#/recipes/${id}`, url: recipe.link ?? null, sourceCommit: texts.source.commit, fingerprint: actual.fingerprint }, hasBody: true, servingEvidence: entry?.servingEvidence ?? [], sourceBlocks: source.blocks, reviewedRelations: relations, reviewedLines: lines.map(line => ({ block: line.block, ...(line.item === undefined ? {} : { item: line.item }), section: line.section, type: line.type, role: line.type === 'heading' ? 'heading' : line.section === '计算' ? 'calculation' : ingredientHeadings.has(line.section) ? 'preparation' : stepHeadings.has(line.section) ? 'operation' : 'source-preserved', findingIndices: findings.flatMap((finding, index) => finding.location.block === line.block && finding.location.item === line.item ? [index] : []) })), quantities: { before: before.quantities, after: after.quantities }, steps: { before: before.steps, after: after.steps }, findings, coverage: { quantitiesBefore: before.quantities.filter((q: any) => q.resolved && q.hasKnownUnit).length, quantitiesAfter: after.quantities.filter((q: any) => q.resolved && q.hasKnownUnit).length, scalableBefore: before.quantities.filter((q: any) => q.scalable && q.hasKnownUnit).length, scalableAfter: after.quantities.filter((q: any) => q.scalable && q.hasKnownUnit).length, stepsBefore: before.steps.length, stepsAfter: after.steps.length } }
})
const summary = {
  catalog: recipes.length,
  withBody: audit.filter(r => r.hasBody).length,
  withoutBody: audit.filter(r => !r.hasBody).length,
  sourceBlocks: Object.values(texts.recipes).reduce((n, t) => n + t.blocks.length, 0),
  sourceTextItems: Object.values(texts.recipes).reduce((n, t) => n + sourceLines(t).length, 0),
  videoOnlyWithoutBody: audit.filter(r => !r.hasBody && (r.source as any).video && !r.source.url).length,
  externalLinkWithoutBody: audit.filter(r => !r.hasBody && r.source.url).length,
  usagesByKind: Object.fromEntries(['amount', 'total', 'remaining', 'unknown', 'reference'].map(kind => [kind, Object.values(annotations.recipes).flatMap(e => e.usages).filter(u => u.kind === kind).length])),
  annotationQuantities: Object.values(annotations.recipes).reduce((n, e) => n + e.quantities.length, 0),
  annotationUsages: Object.values(annotations.recipes).reduce((n, e) => n + e.usages.length, 0),
  byCategory: Object.fromEntries(Object.entries(categories).map(([category, label]) => [category, { label, recipes: audit.filter(r => r.findings.some(f => f.categories.includes(category as Category))).length, findings: audit.flatMap(r => r.findings).filter(f => f.categories.includes(category as Category)).length, unresolvedRecipes: audit.filter(r => r.findings.some(f => f.status === 'needs_confirmation' && f.categories.includes(category as Category))).length }])),
  coverage: Object.fromEntries(['quantitiesBefore', 'quantitiesAfter', 'scalableBefore', 'scalableAfter', 'stepsBefore', 'stepsAfter'].map(k => [k, audit.reduce((sum, r) => sum + ((r as any).coverage?.[k] ?? 0), 0)])),
  recipesWithScalableAmounts: { before: audit.filter(r => (r as any).coverage?.scalableBefore > 0).length, after: audit.filter(r => (r as any).coverage?.scalableAfter > 0).length },
  servingCoveragePercent: { catalogBefore: 100 * audit.filter(r => (r as any).coverage?.scalableBefore > 0).length / recipes.length, catalogAfter: 100 * audit.filter(r => (r as any).coverage?.scalableAfter > 0).length / recipes.length, bodyBefore: 100 * audit.filter(r => (r as any).coverage?.scalableBefore > 0).length / Object.keys(texts.recipes).length, bodyAfter: 100 * audit.filter(r => (r as any).coverage?.scalableAfter > 0).length / Object.keys(texts.recipes).length },
  bodyPendingRecipes: audit.filter(r => r.hasBody && r.findings.some(f => f.status === 'needs_confirmation')).length,
  bodyPendingFindings: audit.filter(r => r.hasBody).flatMap(r => r.findings).filter(f => f.status === 'needs_confirmation').length,
  reviewedIngredientRelations: reviewedRelations.filter(r => r.complete).length,
  reviewedRelationRecipes: new Set(reviewedRelations.map(r => r.id)).size,
  pendingRecipes: audit.filter(r => r.findings.some(f => f.status === 'needs_confirmation')).length,
  pendingFindings: audit.flatMap(r => r.findings).filter(f => f.status === 'needs_confirmation').length,
}
const report = { auditVersion: 1, basis: '当前 recipe.csv 的实际 ID 集合；所有正文块逐条枚举，所有列表项、段落、步骤与原料均检查。未被有限语法完整处理的内容进入待确认，不声称完成任意自然语言语义理解。', inputs: Object.fromEntries(['recipe.csv', 'recipe.json', 'recipe-text.json', 'recipe-amounts.json'].map(file => [file, createHash('sha256').update(fs.readFileSync(`packages/data/${file}`)).digest('hex')])), summary, recipes: audit }
fs.mkdirSync(directory, { recursive: true })
fs.writeFileSync(`${directory}/audit.json`, `${JSON.stringify(report, null, 2)}\n`)
const escape = (text: string) => text.replace(/\|/g, '\\|').replace(/\n/g, '<br>')
let markdown = `# Cook 全目录用量审查\n\n目录 **${summary.catalog}** 道；有正文 **${summary.withBody}** 道，无正文 **${summary.withoutBody}** 道（只有视频 ${summary.videoOnlyWithoutBody} 道，另有外部图文但未内置正文 ${summary.externalLinkWithoutBody} 道）。遍历 ${summary.sourceBlocks} 个正文块、${summary.sourceTextItems} 个标题/段落/列表项。目录 ID 来自 recipe.csv 并与应用 recipe.json 严格比对。\n\n来源：HowToCook ${texts.source.commit}。原文完整保存在 audit.json 的 sourceBlocks；本轮不修改 recipe-text.json、菜谱 ID 或原始步骤文本。\n\n## 方法和可核查边界\n\n审查不以关键词命中条目为全集：逐 ID 枚举；逐正文块分章节；每个原料检查是否唯一关联到实际可读数量；每个计算列表项检查完整覆盖；每个操作检查公式、定量、单位、分次/指代/条件关系；无法完整理解的内容留待确认。关键词只增加风险标签，不作为“其余都已正确”的证明。表中风险分类可重叠，部分是保留原文的已知限制。配图逐块保留元数据，未 OCR；视频和上游 Markdown 在这次离线审查中没有额外转录，未将它们当作新的数字依据。自动规则不能证明理解所有自然语言，报告不把“未命中规则”算作语义通过。\n\n应用覆盖由生产 CookCore 在 1/2/3/20 份实际求值导出，不用 TS 模拟 Swift。before 是同一版本关闭独立标注后的原规则；after 开启标注。覆盖是可读数量/份数公式/步骤卡片指标，不代表所有菜谱已能建立完整投料账本。\n\n| 类别 | 菜谱数 | 定位记录数 | 仍需确认菜谱数 |\n| --- | ---: | ---: | ---: |\n`
for (const c of Object.values(summary.byCategory)) markdown += `| ${c.label} | ${c.recipes} | ${c.findings} | ${c.unresolvedRecipes} |\n`
markdown += `\n## 应用读取覆盖\n\n| 指标 | 标注前 | 标注后 |\n| --- | ---: | ---: |\n| 可读取定量材料行（含条件量） | ${summary.coverage.quantitiesBefore} | ${summary.coverage.quantitiesAfter} |\n| 有明确份数依赖的材料行 | ${summary.coverage.scalableBefore} | ${summary.coverage.scalableAfter} |\n| 至少一项材料可随份数换算的菜谱 | ${summary.recipesWithScalableAmounts.before} / ${summary.catalog} | ${summary.recipesWithScalableAmounts.after} / ${summary.catalog} |\n| 进入步骤卡片的原始操作 | ${summary.coverage.stepsBefore} | ${summary.coverage.stepsAfter} |\n\n“至少一项可随份数换算”的全目录覆盖率 ${summary.servingCoveragePercent.catalogBefore.toFixed(2)}% → ${summary.servingCoveragePercent.catalogAfter.toFixed(2)}%；在 232 道有正文的固定集合中为 ${summary.servingCoveragePercent.bodyBefore.toFixed(2)}% → ${summary.servingCoveragePercent.bodyAfter.toFixed(2)}%。这不是整菜完整投料账本的完成率。\n\n新增结构化数量 ${summary.annotationQuantities} 条；步骤账本 ${summary.annotationUsages} 条，其中明确数量 ${summary.usagesByKind.amount} 条、总量 ${summary.usagesByKind.total} 条、余量 ${summary.usagesByKind.remaining} 条、不明确事件阻断 ${summary.usagesByKind.unknown} 条、非消耗引用 ${summary.usagesByKind.reference} 条。其余不确定关系保守阻断，不能用总量覆盖分次用量。\n\n## 阅读待确认清单\n\n本轮有正文菜谱待确认 **${summary.bodyPendingRecipes}** 道、**${summary.bodyPendingFindings}** 条；优先看 [仅有正文清单](pending-with-body.md)。第二轮逐篇复核关系 ${summary.reviewedIngredientRelations} 组，涉及 ${summary.reviewedRelationRecipes} 道；完整原句与阶段事件在 followup-relations.json，增量说明见 [followup.md](followup.md)。\n\n[逐菜待确认](pending.md) 包含 ${summary.pendingRecipes} 道菜、${summary.pendingFindings} 条定位记录。每条带 ID/名称、源链接、章节/块/列表项/步骤、原句、原因、可确定性和需要补充的信息。无正文的 ${summary.withoutBody} 道全部列出。audit.json 同时记录已确定标注、固定量和条件量；pending.json 提供同样的未解决项供后续处理。\n\n## 安全回退\n\n独立 recipe-amounts.json 采用整篇 FNV-1a 指纹和逐条原句锚点；指纹不是安全签名。任何来源漂移、锚点不符、无效结构均弃用该菜标注，继续显示原文和已有有限规则。schema 版本未知或文件损坏时整个标注库不采用。份数不改原始步骤身份；投料按源顺序计算，完成勾选不参与扣减。\n\n不推定缺失份数；不把固定量自动乘份数；不在质量与体积间无依据互转；范围前序实际用量未确定、可选分支未选择、超量时不算确定余量。固定批次与每份子配方分开处理。\n\n## 复现\n\n在本工作树运行：\n\n\`\`\`bash\npnpm amounts:check\npnpm audit:amounts\n\`\`\`\n\n需要调整标注时先逐条核对来源，再显式运行 \`pnpm amounts:annotate\` 并审阅数据差异；导入和审查不会自动重新签署变化的原文。所有运行使用任务独占临时目录，Swift 导出完成后 finally 回收编译器产物。\n\n本轮完整验证与回收记录见 [verification.md](verification.md)、[verification.json](verification.json) 和 [cleanup.json](cleanup.json)。\n`
fs.writeFileSync(`${directory}/README.md`, markdown)
const pending = audit.filter(r => r.findings.some(f => f.status === 'needs_confirmation')).map(r => ({ id: r.id, name: r.name, source: r.source, findings: r.findings.filter(f => f.status === 'needs_confirmation') }))
fs.writeFileSync(`${directory}/pending.json`, `${JSON.stringify(pending, null, 2)}\n`)
const bodyPending = pending.filter(r => texts.recipes[r.id])
fs.writeFileSync(`${directory}/pending-with-body.json`, `${JSON.stringify(bodyPending, null, 2)}\n`)
function renderPending(list: typeof pending) {
  let pendingMarkdown = '# Cook 用量待确认清单\n\n所有定位的 block/item 为 0 起始，步骤编号沿用 App 展示；原句中的换行用 <br> 保留。determinable=false 表示当前依据不足以完成该关系，不等同于原文有错。\n'
  for (const r of list) {
    pendingMarkdown += `\n## ${r.id} ${r.name}\n\n来源：${r.source.url ? `[原文](${r.source.url})` : (r.source as any).video ? `[B站 ${(r.source as any).video}](https://www.bilibili.com/video/${(r.source as any).video})（无内置转录）` : '目录无正文'}；\`${r.source.file}\`${(r.source as any).line ? ` 第 ${(r.source as any).line} 行` : ''}。\n\n| 章节/定位 | 类别 | 原句 | 当前原因 | 可确定 | 需要确认 |\n| --- | --- | --- | --- | --- | --- |\n`
    for (const f of r.findings) pendingMarkdown += `| ${escape(f.location.section)} / block ${f.location.block ?? '-'} / item ${f.location.item ?? '-'}${f.location.step === undefined ? '' : ` / 第${f.location.step}步`} | ${f.categories.map(c => categories[c]).join('、')} | ${escape(f.quote)} | ${escape(f.reason)} | 否 | ${escape(f.confirmation)} |\n`
  }
  return pendingMarkdown
}
fs.writeFileSync(`${directory}/pending.md`, renderPending(pending))
fs.writeFileSync(`${directory}/pending-with-body.md`, renderPending(bodyPending).replace('# Cook 用量待确认清单', '# Cook 有正文菜谱待确认清单'))
console.log(JSON.stringify(summary, null, 2))
