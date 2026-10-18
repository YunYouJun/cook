import type { RecipeItem } from '../types/index.js'
import { STUFF_ALIASES } from './alias.js'

export const DIFFICULTIES = ['简单', '普通', '困难'] as const

/** B 站视频号：BV 号或旧版 av 号 */
const VIDEO_ID_RE = /^(?:BV[0-9A-Za-z]{10}|av\d+)$/
/** 单元格内应使用顿号分隔，出现这些字符通常是录入错误 */
const BAD_SEP_RE = /[,，/／]/

export interface RecipeDictionary {
  /** 食材标准名 */
  stuff: Set<string>
  /** 厨具标准名 */
  tools: Set<string>
}

export interface RecipeIssue {
  level: 'error' | 'warning'
  /** CSV 中的行号（含表头，从 1 开始） */
  line: number
  name: string
  message: string
}

export interface RecipeStats {
  total: number
  missingDifficulty: number
  missingMethods: number
  missingVideo: number
}

export interface ValidateOptions {
  /** 基准版本（如 main 分支）的菜谱，用来检查 id 是否被改动或复用 */
  base?: RecipeItem[]
}

export interface ValidateResult {
  issues: RecipeIssue[]
  stats: RecipeStats
}

function suggestStuff(value: string, dict: RecipeDictionary) {
  const alias = STUFF_ALIASES[value]
  if (alias && dict.stuff.has(alias))
    return alias
}

/**
 * 校验菜谱数据：错误会让 CI 失败，警告只做提示
 */
export function validateRecipes(recipes: RecipeItem[], dict: RecipeDictionary, options: ValidateOptions = {}): ValidateResult {
  const issues: RecipeIssue[] = []
  const stats: RecipeStats = { total: recipes.length, missingDifficulty: 0, missingMethods: 0, missingVideo: 0 }
  const seen = new Map<string, { line: number, bv?: string }[]>()
  const ids = new Map<number, number>()
  const maxId = Math.max(-1, ...recipes.map(r => r.id ?? -1))

  recipes.forEach((recipe, i) => {
    const line = i + 2
    const name = recipe.name
    const error = (message: string) => issues.push({ level: 'error', line, name, message })
    const warn = (message: string) => issues.push({ level: 'warning', line, name, message })

    if (!name)
      error('缺少菜名')

    if (recipe.id === undefined) {
      error(`缺少 id，新菜谱请用 ${maxId + 1}（当前最大 id + 1）`)
    }
    else if (ids.has(recipe.id)) {
      error(`id ${recipe.id} 与第 ${ids.get(recipe.id)} 行重复`)
    }
    else {
      ids.set(recipe.id, line)
    }

    if (!recipe.stuff.length)
      error('缺少食材')
    for (const item of recipe.stuff) {
      if (BAD_SEP_RE.test(item)) {
        error(`食材「${item}」里有逗号或斜杠，请用顿号「、」分隔`)
        continue
      }
      if (!dict.stuff.has(item)) {
        const suggestion = suggestStuff(item, dict)
        error(suggestion
          ? `食材「${item}」请改为标准名「${suggestion}」`
          : `食材「${item}」不在食材词典中（app/data/food.ts），请改为已有食材或先加入词典`)
      }
    }

    for (const tool of recipe.tools) {
      if (!dict.tools.has(tool))
        error(`厨具「${tool}」不在厨具词典中（app/data/food.ts）`)
    }

    if (recipe.difficulty && !(DIFFICULTIES as readonly string[]).includes(recipe.difficulty))
      error(`难度「${recipe.difficulty}」应为 ${DIFFICULTIES.join(' / ')} 之一`)
    if (!recipe.difficulty)
      stats.missingDifficulty++

    for (const method of recipe.methods ?? []) {
      if (BAD_SEP_RE.test(method))
        error(`做法「${method}」里有逗号或斜杠，请用顿号「、」分隔`)
    }
    if (!recipe.methods?.length)
      stats.missingMethods++

    if (recipe.bv && !VIDEO_ID_RE.test(recipe.bv))
      error(`视频号「${recipe.bv}」格式不对，应为 BV 号或 av 号`)
    if (!recipe.bv && !recipe.link)
      stats.missingVideo++

    const same = seen.get(name) ?? []
    const twin = same.find(other => other.bv === recipe.bv)
    // 重复只提示：删掉其中一行会让收藏了它的用户丢失这条收藏，交给人决定
    if (twin)
      warn(`与第 ${twin.line} 行重复（菜名和视频都相同）`)
    else if (same.length)
      warn(`与第 ${same.map(s => s.line).join('、')} 行同名但视频不同，确认是否为不同做法`)
    same.push({ line, bv: recipe.bv })
    seen.set(name, same)
  })

  if (options.base)
    issues.push(...compareWithBase(recipes, options.base))

  return { issues, stats }
}

/**
 * id 一旦发布就固定：可以改菜名或视频，但不能把 id 改给另一道菜，也不能复用已删除的 id
 */
function compareWithBase(recipes: RecipeItem[], base: RecipeItem[]): RecipeIssue[] {
  const issues: RecipeIssue[] = []
  const baseById = new Map(base.filter(r => r.id !== undefined).map(r => [r.id!, r]))
  const baseMax = Math.max(-1, ...baseById.keys())
  const current = new Set<number>()

  recipes.forEach((recipe, i) => {
    if (recipe.id === undefined)
      return
    current.add(recipe.id)
    const before = baseById.get(recipe.id)
    const issue = (message: string) => issues.push({ level: 'error', line: i + 2, name: recipe.name, message })
    if (before && before.name !== recipe.name && before.bv !== recipe.bv)
      issue(`id ${recipe.id} 原来是「${before.name}」，菜名和视频都变了，像是把 id 给了另一道菜；新菜谱请用新的 id`)
    else if (!before && recipe.id <= baseMax)
      issue(`id ${recipe.id} 是已删除菜谱的 id，不能复用；新菜谱请用大于 ${baseMax} 的 id`)
  })

  for (const [id, before] of baseById) {
    if (!current.has(id))
      issues.push({ level: 'warning', line: 0, name: before.name, message: `删除了 id ${id}，收藏过它的用户会丢失这条收藏` })
  }
  return issues
}
