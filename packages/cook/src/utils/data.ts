import type { IncompatibleRule, RecipeItem } from '../../types.js'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import consola from 'consola'
import { incompatibleFoodsJsonFile, recipeJsonFile } from './config.js'

/**
 * 加载 recipe.json
 * @param dataDir 自定义数据目录（覆盖默认路径）
 */
export function loadRecipes(dataDir?: string): RecipeItem[] {
  const filePath = dataDir
    ? path.resolve(dataDir, 'recipe.json')
    : recipeJsonFile

  if (!fs.existsSync(filePath)) {
    consola.error(`找不到 ${filePath}，请先执行 \`cook convert\` 生成 JSON 数据。`)
    process.exit(1)
  }

  return JSON.parse(fs.readFileSync(filePath, 'utf-8'))
}

/**
 * 加载 incompatible-foods.json
 * @param dataDir 自定义数据目录（覆盖默认路径）
 */
export function loadIncompatibleFoods(dataDir?: string): IncompatibleRule[] {
  const filePath = dataDir
    ? path.resolve(dataDir, 'incompatible-foods.json')
    : incompatibleFoodsJsonFile

  if (!fs.existsSync(filePath)) {
    consola.warn(`找不到 ${filePath}，食物相克数据不可用。`)
    return []
  }

  return JSON.parse(fs.readFileSync(filePath, 'utf-8'))
}
