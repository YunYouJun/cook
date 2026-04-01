import type { RecipeItem } from '../../types.js'
import process from 'node:process'
import consola from 'consola'
import { loadRecipes } from '../utils/data.js'
import { formatRecipeList, output } from '../utils/format.js'

/**
 * Fisher-Yates partial shuffle 安全取样
 * 不会在 count > length 时无限循环
 */
export function sampleRecipes(recipes: RecipeItem[], count: number): RecipeItem[] {
  const n = Math.min(count, recipes.length)
  const arr = [...recipes]
  for (let i = arr.length - 1; i > arr.length - 1 - n && i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [arr[i], arr[j]] = [arr[j]!, arr[i]!]
  }
  return arr.slice(arr.length - n)
}

export function runRandom(options: {
  count?: number
  json?: boolean
  dataDir?: string
}) {
  const { count = 3, json = false, dataDir } = options

  if (count < 1) {
    consola.error('--count 必须大于 0')
    process.exit(1)
  }

  const recipes = loadRecipes(dataDir)

  if (count > recipes.length) {
    consola.warn(`请求 ${count} 道菜，但数据库只有 ${recipes.length} 道，将返回全部。`)
  }

  const result = sampleRecipes(recipes, count)

  if (json) {
    output(result, true)
  }
  else {
    consola.log(`🎲 随机推荐 ${result.length} 道菜谱\n`)
    consola.log(formatRecipeList(result))
  }
}
