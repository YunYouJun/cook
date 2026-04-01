import type { Difficulty, RecipeItem } from '../../types.js'
import process from 'node:process'
import consola from 'consola'
import { loadRecipes } from '../utils/data.js'
import { formatRecipeList, output } from '../utils/format.js'

export function runList(options: {
  keyword?: string
  difficulty?: string
  limit?: number
  json?: boolean
  dataDir?: string
}) {
  const { keyword, difficulty, limit = 20, json = false, dataDir } = options

  const validDifficulties: Difficulty[] = ['简单', '普通', '困难']
  if (difficulty && !validDifficulties.includes(difficulty as Difficulty)) {
    consola.error(`无效的难度: ${difficulty}。可选: 简单, 普通, 困难`)
    process.exit(1)
  }

  const recipes = loadRecipes(dataDir)
  let result: RecipeItem[] = recipes

  if (keyword)
    result = result.filter(r => r.name.includes(keyword))

  if (difficulty)
    result = result.filter(r => r.difficulty === difficulty)

  const total = result.length
  if (limit > 0)
    result = result.slice(0, limit)

  if (json) {
    output(result, true)
  }
  else {
    const filters: string[] = []
    if (keyword)
      filters.push(`关键词="${keyword}"`)
    if (difficulty)
      filters.push(`难度=${difficulty}`)
    const filterStr = filters.length > 0 ? `（筛选: ${filters.join(', ')}）` : ''

    if (total === 0) {
      consola.info(`未找到匹配的菜谱${filterStr}。`)
    }
    else {
      const showing = limit > 0 && total > limit ? `显示前 ${limit} 条` : `共 ${total} 条`
      consola.log(`共 ${recipes.length} 道菜谱，${showing}${filterStr}\n`)
      consola.log(formatRecipeList(result))
      if (limit > 0 && total > limit)
        consola.log(`\n共匹配 ${total} 条。使用 --limit 0 显示全部。`)
    }
  }
}
