import type { RecipeItem } from '../types/recipe'
import { normalizeStuff } from './alias'

export type RecommendationMode = 'loose' | 'strict' | 'survival'

/**
 * 兜底大杂烩（火锅、炸万物等）列的是「能放什么」而不是「必须有什么」。
 * 数据里这类菜都在 10 种食材以上，普通菜最多 8 种，以此区分。
 */
export const CATCH_ALL_MIN_STUFF = 10
/** 大杂烩至少要用上这么多种已选食材才推荐，避免选一样就冒出来 */
export const CATCH_ALL_MIN_MATCH = 2

export interface RecipeMatch {
  recipe: RecipeItem
  /** Stable position in the source data, also used to break sorting ties. */
  sourceIndex: number
  requiredStuff: string[]
  matchedStuff: string[]
  pantryStuff: string[]
  missingStuff: string[]
  /** 大杂烩里没选、但可以随意加的食材；普通菜为空 */
  optionalStuff: string[]
}

export interface RecommendationOptions {
  stuff: string[]
  pantry?: string[]
  tool?: string
  keyword?: string
  mode?: RecommendationMode
  /** 忌口：普通菜只要用到就排除，大杂烩只是不再建议加它 */
  avoid?: string[]
  /** 做法分组（见 METHOD_GROUPS），选了多个时满足任意一个即可 */
  methodGroups?: string[]
  /** 不吃辣：排除打了「辣」标签的菜 */
  noSpicy?: boolean
}

/**
 * 筛选用的做法分组：数据里的做法写法很散（烤/烘/焗、拌/凉拌），按吃起来的区别归成几类。
 * 汤羹不是做法，靠「汤」标签识别。
 */
export const METHOD_GROUPS: { label: string, methods: string[], tags?: string[] }[] = [
  { label: '炒菜', methods: ['炒'] },
  { label: '汤羹', methods: [], tags: ['汤'] },
  { label: '炖煮', methods: ['炖', '煮', '煲', '卤', '焖', '烧', '灼'] },
  { label: '蒸', methods: ['蒸'] },
  { label: '炸煎', methods: ['炸', '煎', '烙'] },
  { label: '烤', methods: ['烤', '烘', '焗'] },
  { label: '凉拌', methods: ['拌', '凉拌', '腌', '腌制', '泡菜', '油泼'] },
  { label: '微波', methods: ['微波加热'] },
]

export const SPICY_TAG = '辣'

/** Reuse the catalog aliases; split recorded separators, trim and deduplicate. */
export function uniqueStuff(stuff: string[]): string[] {
  return [...new Set(normalizeStuff(stuff.flatMap(item => item.split(/[,，、;；\n]/))).filter(Boolean))]
}

function isCatchAll(requiredStuff: string[]): boolean {
  return requiredStuff.length >= CATCH_ALL_MIN_STUFF
}

/** 菜里有没有忌口的食材；大杂烩里的食材都是可选的，不算 */
export function hasAvoidedStuff(recipe: RecipeItem, avoid: string[]): boolean {
  if (!avoid.length)
    return false
  const requiredStuff = uniqueStuff(recipe.stuff)
  if (isCatchAll(requiredStuff))
    return false
  const avoided = new Set(uniqueStuff(avoid))
  return requiredStuff.some(item => avoided.has(item))
}

function recipeMethods(recipe: RecipeItem): string[] {
  return ((recipe.methods ?? []) as string[]).flatMap(method => method.split(/[、/]/)).filter(Boolean)
}

/** 做法和口味筛选；没有记录做法的菜在选了做法分组时不会出现 */
export function matchesTaste(recipe: RecipeItem, options: Pick<RecommendationOptions, 'methodGroups' | 'noSpicy'>): boolean {
  if (options.noSpicy && recipe.tags?.includes(SPICY_TAG))
    return false
  const groups = METHOD_GROUPS.filter(group => options.methodGroups?.includes(group.label))
  if (!groups.length)
    return true
  const methods = recipeMethods(recipe)
  return groups.some(group => group.methods.some(method => methods.includes(method))
    || group.tags?.some(tag => recipe.tags?.includes(tag)))
}

/** Only explicitly recorded ingredients are requirements. Recipe names are never parsed. */
export function recommendRecipes(recipes: RecipeItem[], options: RecommendationOptions): RecipeMatch[] {
  const selected = new Set(uniqueStuff(options.stuff))
  const pantry = new Set(uniqueStuff(options.pantry ?? []))
  const keyword = options.keyword?.trim() ?? ''
  const avoid = options.avoid ?? []
  const avoided = new Set(uniqueStuff(avoid))
  if (!selected.size)
    return []

  return recipes.flatMap((recipe, sourceIndex): RecipeMatch[] => {
    if (options.tool && !recipe.tools?.includes(options.tool))
      return []
    if (keyword && !recipe.name.includes(keyword))
      return []
    if (hasAvoidedStuff(recipe, avoid) || !matchesTaste(recipe, options))
      return []

    const requiredStuff = uniqueStuff(recipe.stuff)
    const matchedStuff = requiredStuff.filter(item => selected.has(item))
    const pantryStuff = requiredStuff.filter(item => !selected.has(item) && pantry.has(item))
    const unownedStuff = requiredStuff.filter(item => !selected.has(item) && !pantry.has(item))
    const catchAll = isCatchAll(requiredStuff)
    const missingStuff = catchAll ? [] : unownedStuff
    const optionalStuff = catchAll ? unownedStuff.filter(item => !avoided.has(item)) : []

    // Pantry alone must not create an unrelated recommendation; empty records are unknown.
    if (!matchedStuff.length || missingStuff.length > 2)
      return []
    if (catchAll && matchedStuff.length < CATCH_ALL_MIN_MATCH)
      return []
    if (options.mode === 'strict' && ![...selected].every(item => requiredStuff.includes(item)))
      return []
    if (options.mode === 'survival' && missingStuff.length)
      return []

    return [{ recipe, sourceIndex, requiredStuff, matchedStuff, pantryStuff, missingStuff, optionalStuff }]
  }).sort((a, b) =>
    a.missingStuff.length - b.missingStuff.length
    // 大杂烩是兜底选项，排在同组的正经菜后面
    || Number(isCatchAll(a.requiredStuff)) - Number(isCatchAll(b.requiredStuff))
    || b.matchedStuff.length - a.matchedStuff.length
    || a.sourceIndex - b.sourceIndex,
  )
}
