import type { IncompatibleRule } from '../types/incompatible-foods'

/** 已选食材里同时出现的相克组合；少于两样食材时不检查 */
export function findIncompatibleRules(ingredients: string[], rules: IncompatibleRule[]): IncompatibleRule[] {
  if (ingredients.length < 2)
    return []
  const selected = new Set(ingredients)
  return rules.filter(rule => selected.has(rule.foodA) && selected.has(rule.foodB))
}
