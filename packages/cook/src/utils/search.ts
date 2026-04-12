import type { IncompatibleRule, RecipeItem } from '../types/index.js'
import { normalizeStuff } from './alias.js'

export interface SearchOptions {
  /** 食材列表 */
  stuff?: string[]
  /** 厨具 */
  tool?: string
  /** 难度 */
  difficulty?: string
  /** 标签 */
  tag?: string
  /** 烹饪方式 */
  method?: string
  /** 最大返回数 */
  limit?: number
}

export interface SearchResultItem extends RecipeItem {
  /** 匹配的食材 */
  matchedStuff: string[]
  /** 缺少的食材 */
  missingStuff: string[]
  /** 视频链接 */
  videoUrl?: string
}

export interface SearchResult {
  recipes: SearchResultItem[]
  warnings: IncompatibleRule[]
}

export function searchRecipes(
  recipes: RecipeItem[],
  incompatible: IncompatibleRule[],
  options: SearchOptions,
): SearchResult {
  const { stuff = [], tool, difficulty, tag, method, limit = 10 } = options

  const normalized = normalizeStuff(stuff)
  const stuffLower = normalized.map(s => s.toLowerCase())

  let matched = recipes.filter((r) => {
    if (stuffLower.length > 0) {
      const has = r.stuff.some(s => stuffLower.includes(s.toLowerCase()))
      if (!has)
        return false
    }
    if (tool && !r.tools.some(t => t.includes(tool)))
      return false
    if (difficulty && r.difficulty !== difficulty)
      return false
    if (tag && !r.tags?.some(t => t.includes(tag)))
      return false
    if (method && !r.methods?.some(m => m.includes(method)))
      return false
    return true
  })

  // 按匹配食材数排序（多的优先），相同时食材更少的优先
  if (stuffLower.length > 0) {
    matched.sort((a, b) => {
      const aCount = a.stuff.filter(s => stuffLower.includes(s.toLowerCase())).length
      const bCount = b.stuff.filter(s => stuffLower.includes(s.toLowerCase())).length
      if (bCount === aCount)
        return a.stuff.length - b.stuff.length
      return bCount - aCount
    })
  }

  matched = matched.slice(0, limit)

  const resultRecipes: SearchResultItem[] = matched.map((r) => {
    const matchedStuff = r.stuff.filter(s => stuffLower.includes(s.toLowerCase()))
    const missingStuff = r.stuff.filter(s => !stuffLower.includes(s.toLowerCase()))
    return {
      ...r,
      matchedStuff,
      missingStuff,
      videoUrl: r.bv ? `https://www.bilibili.com/video/${r.bv}` : undefined,
    }
  })

  // 检查食物相克
  const warnings: IncompatibleRule[] = []
  if (stuffLower.length >= 2) {
    for (const rule of incompatible) {
      const hasA = stuffLower.includes(rule.foodA.toLowerCase())
      const hasB = stuffLower.includes(rule.foodB.toLowerCase())
      if (hasA && hasB)
        warnings.push(rule)
    }
  }

  return { recipes: resultRecipes, warnings }
}
