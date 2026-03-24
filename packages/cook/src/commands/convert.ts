import fs from 'node:fs'
import path from 'node:path'
import consola from 'consola'
import {
  incompatibleFoodsCsvFile,
  incompatibleFoodsJsonFile,
  recipeCsvFile,
  recipeJsonFile,
  rootDir,
} from '../utils/config.js'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../utils/csv.js'

// 正则表达式预编译,避免每次调用时重新编译
const DATE_SLASH_REGEX = /\//g
const LAST_DB_UPDATED_REGEX = /export const lastDbUpdated = '.+'/

/**
 * 转换 recipe CSV → JSON
 */
function convertRecipes() {
  consola.info('Converting recipe data...')

  const csvData = fs.readFileSync(recipeCsvFile, 'utf-8')
  const recipes = parseRecipeCsv(csvData)

  if (recipes.length === 0) {
    consola.warn('No recipes parsed, skipping JSON generation')
    return
  }

  fs.writeFileSync(recipeJsonFile, JSON.stringify(recipes))
  consola.success(`Generated: ${recipeJsonFile} (${recipes.length} recipes)`)
}

/**
 * 转换食物相克 CSV → JSON
 */
function convertIncompatibleFoods() {
  consola.info('Converting incompatible foods data...')

  try {
    const csvData = fs.readFileSync(incompatibleFoodsCsvFile, 'utf-8')
    const rules = parseIncompatibleFoodsCsv(csvData)

    if (rules.length === 0) {
      consola.warn('No rules parsed, skipping JSON generation')
      return
    }

    fs.writeFileSync(incompatibleFoodsJsonFile, JSON.stringify(rules, null, 2))
    consola.success(`Generated: ${incompatibleFoodsJsonFile} (${rules.length} rules)`)
  }
  catch (error) {
    consola.error('Failed to convert incompatible foods data:', error)
  }
}

/**
 * 更新 lastDbUpdated 时间戳
 */
function updateLastDbUpdated() {
  const constantsFile = path.join(rootDir, 'app/constants/index.ts')

  if (!fs.existsSync(constantsFile)) {
    consola.warn('Constants file not found, skipping timestamp update')
    return
  }

  const content = fs.readFileSync(constantsFile, 'utf-8')
  const now = new Date().toLocaleString('zh-CN', {
    timeZone: 'Asia/Shanghai',
    hour12: false,
  }).replace(DATE_SLASH_REGEX, '-')

  const updated = content.replace(
    LAST_DB_UPDATED_REGEX,
    `export const lastDbUpdated = '${now}'`,
  )

  if (content !== updated) {
    fs.writeFileSync(constantsFile, updated)
    consola.success(`Updated lastDbUpdated to: ${now}`)
  }
}

/**
 * convert 命令入口
 */
export function runConvert() {
  convertRecipes()
  convertIncompatibleFoods()
  updateLastDbUpdated()
}
