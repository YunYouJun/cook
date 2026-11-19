import type { Difficulty, IncompatibleRule, RecipeItem } from '../types/index.js'
import type { CookQuery, RecommendationMode } from './recommend.js'
import fs from 'node:fs'
import pantryData from '../../../data/pantry.json'
import {
  incompatibleFoodsCsvFile,
  recipeCsvFile,
  videoStatsJsonFile,
} from './config.js'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from './csv.js'
import { findIncompatibleRules } from './incompatible.js'
import { parseCookQuery } from './query.js'
import { DIFFICULTY_LEVELS, recommendRecipes, uniqueStuff } from './recommend.js'
import { applyRecipeScores } from './score.js'
import { searchRecipes } from './search.js'
import { readVideoStats } from './video-stats.js'

export interface CookCatalogData {
  recipes: RecipeItem[]
  incompatible: IncompatibleRule[]
  defaultPantry: string[]
}

export interface AgentQueryOptions {
  /** 自然语言整句（如 "冰箱里有土豆和鸡蛋，不要辣，用空气炸锅"） */
  query?: string
  /** 食材列表（支持别名和分隔符） */
  stuff?: string[]
  /** 忌口食材 */
  avoid?: string[]
  /** 厨具 */
  tool?: string
  /** 难度：简单 / 普通 / 困难 */
  difficulty?: string
  /** 标签 */
  tag?: string
  /** 烹饪方式（单个字词或做法分组名） */
  method?: string
  /** 不吃辣 */
  noSpicy?: boolean
  /** 推荐模式：loose / strict / survival */
  mode?: RecommendationMode
  /** 常备调料（默认使用 pantry.json 的 defaults） */
  pantry?: string[]
  /** 最大返回数（默认 10） */
  limit?: number
}

export interface AgentRecipeResult extends RecipeItem {
  matchedStuff: string[]
  pantryStuff?: string[]
  missingStuff: string[]
  optionalStuff?: string[]
  videoUrl?: string
}

export interface AgentQueryResult {
  recipes: AgentRecipeResult[]
  warnings: IncompatibleRule[]
  parsedQuery?: CookQuery
  engine: 'recommend' | 'search'
}

export function loadCookCatalog(): CookCatalogData {
  const rawRecipes = parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8'))
  const recipes = applyRecipeScores(rawRecipes, readVideoStats(videoStatsJsonFile))

  let incompatible: IncompatibleRule[] = []
  try {
    incompatible = parseIncompatibleFoodsCsv(fs.readFileSync(incompatibleFoodsCsvFile, 'utf-8'))
  }
  catch {
    incompatible = []
  }

  return {
    recipes,
    incompatible,
    defaultPantry: [...pantryData.defaults],
  }
}

function isDifficulty(value: string): value is Difficulty {
  return (DIFFICULTY_LEVELS as string[]).includes(value)
}

/**
 * 面向 CLI 与 MCP Agent 的统一菜谱检索：
 * - 当传入自然语言 query、忌口 avoid、不吃辣 noSpicy 或推荐模式 mode 时，调用与 Web/iOS/Android 一致的推荐引擎。
 * - 否则保持经典 searchRecipes 行为（向后兼容旧 CLI 调用）。
 */
export function queryCookCatalog(
  catalog: CookCatalogData,
  options: AgentQueryOptions,
): AgentQueryResult {
  const limit = options.limit ?? 10
  const parsedQuery = options.query?.trim() ? parseCookQuery(options.query) : undefined
  const mergedStuff = uniqueStuff([
    ...(parsedQuery?.stuff ?? []),
    ...(parsedQuery?.unknown ?? []),
    ...(options.stuff ?? []),
  ])
  const mergedAvoid = uniqueStuff([
    ...(parsedQuery?.avoid ?? []),
    ...(options.avoid ?? []),
  ])
  const mergedTool = options.tool || parsedQuery?.tool
  const mergedNoSpicy = Boolean(options.noSpicy || parsedQuery?.noSpicy)
  const mergedDifficulties: Difficulty[] = [
    ...(parsedQuery?.difficulty ?? []),
    ...(options.difficulty && isDifficulty(options.difficulty) ? [options.difficulty] : []),
  ]
  const uniqueDifficulties = [...new Set(mergedDifficulties)]

  const useRecommendEngine = Boolean(
    parsedQuery
    || mergedAvoid.length > 0
    || mergedNoSpicy
    || options.mode
    || options.pantry,
  )

  if (!useRecommendEngine) {
    const result = searchRecipes(catalog.recipes, catalog.incompatible, {
      stuff: mergedStuff,
      tool: mergedTool,
      difficulty: options.difficulty,
      tag: options.tag,
      method: options.method,
      limit,
    })
    return {
      recipes: result.recipes,
      warnings: result.warnings,
      engine: 'search',
    }
  }

  let matches = recommendRecipes(catalog.recipes, {
    stuff: mergedStuff,
    pantry: options.pantry ?? catalog.defaultPantry,
    tool: mergedTool,
    mode: options.mode ?? 'loose',
    avoid: mergedAvoid,
    methodGroups: parsedQuery?.methodGroups,
    noSpicy: mergedNoSpicy,
    difficulty: uniqueDifficulties.length > 0 ? uniqueDifficulties : undefined,
  })

  if (options.tag) {
    const tag = options.tag
    matches = matches.filter(m => m.recipe.tags?.some(t => t.includes(tag)))
  }
  if (options.method) {
    const method = options.method
    matches = matches.filter(m => m.recipe.methods?.some(item => item.includes(method)))
  }

  const sliced = matches.slice(0, Math.max(0, limit))
  const recipes: AgentRecipeResult[] = sliced.map(match => ({
    ...match.recipe,
    matchedStuff: match.matchedStuff,
    pantryStuff: match.pantryStuff,
    missingStuff: match.missingStuff,
    optionalStuff: match.optionalStuff,
    videoUrl: match.recipe.bv ? `https://www.bilibili.com/video/${match.recipe.bv}` : undefined,
  }))

  const warnings = findIncompatibleRules(mergedStuff, catalog.incompatible)

  return {
    recipes,
    warnings,
    parsedQuery,
    engine: 'recommend',
  }
}
