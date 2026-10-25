import type { RecipeTextData } from '../../packages/cook/src/types/recipe-text'
import type { AmountAnnotations, AmountFormula } from './recipe-amounts'
import { key, matchesAnchor, sourceFingerprint } from './recipe-amounts'

/** 语义校验和 JSON Schema 分开，供 CLI、单元测试及导入流程复用。 */
export function validateAmountAnnotations(data: AmountAnnotations, texts: RecipeTextData, ids: Set<number>) {
  const errors: string[] = []
  for (const [id, entry] of Object.entries(data.recipes)) {
    const fail = (reason: string) => errors.push(`${id}: ${reason}`)
    const source = texts.recipes[id]
    if (!ids.has(Number(id)) || !source) {
      fail('标注 ID 无目录或正文依据')
      continue
    }
    if (entry.sourceFingerprint !== sourceFingerprint(source))
      fail('来源指纹失配，需要重新逐条核对，不能自动重签')
    const allAnchors = [...entry.servingEvidence, ...entry.stepSections, ...entry.quantities.flatMap(q => q.evidence), ...entry.usages.flatMap(u => [u.anchor, ...u.evidence])]
    for (const anchor of allAnchors) {
      if (!matchesAnchor(anchor, source))
        fail(`原文锚点失配 block=${anchor.block} item=${anchor.item ?? '-'}`)
    }
    if (entry.stepSections.some(a => source.blocks[a.block]?.type !== 'heading'))
      fail('步骤章节锚点不是标题')
    const owners = new Map<string, string>()
    const names = new Set<string>()
    for (const quantity of entry.quantities) {
      if (names.has(key(quantity.name)))
        fail(`重复用量 ${quantity.name}`)
      names.add(key(quantity.name))
      for (const alias of [quantity.name, ...quantity.aliases]) {
        if (owners.has(alias) && owners.get(alias) !== quantity.name)
          fail(`别名同时指向不同材料 ${alias}`)
        owners.set(alias, quantity.name)
      }
    }
    const walk = (formula: AmountFormula, depth = 0) => {
      if (depth >= 16) {
        fail('公式嵌套超过 16')
        return
      }
      if (formula.op === 'literal' && (formula.upper ?? formula.lower) < formula.lower)
        fail('范围上下限颠倒')
      if ('args' in formula)
        formula.args.forEach(f => walk(f, depth + 1))
      if (formula.op === 'divide' && formula.args[1]?.op === 'scalar' && formula.args[1].lower <= 0)
        fail('除数必须为正')
    }
    entry.quantities.forEach(q => walk(q.formula))
    const usageKeys = new Set<string>()
    for (const usage of entry.usages) {
      const usageKey = `${usage.anchor.block}:${usage.anchor.item ?? -1}:${usage.ingredient}:${usage.kind}:${usage.target ?? ''}`
      if (usageKeys.has(usageKey))
        fail('步骤投料记录重复')
      usageKeys.add(usageKey)
      if (usage.kind === 'amount' && !usage.formula)
        fail('amount 缺少公式')
      if (usage.kind !== 'unknown' && (!usage.target || usage.anchor.text.split(usage.target).length !== 2))
        fail('步骤替换/插入片段不唯一')
      if (usage.formula)
        walk(usage.formula)
    }
  }
  return errors
}
