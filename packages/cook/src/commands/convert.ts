import crypto from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import consola from 'consola'
import {
  CHUNK_SIZE,
  CHUNK_THRESHOLD,
  DATA_SCHEMA_VERSION,
  incompatibleFoodsCsvFile,
  incompatibleFoodsJsonFile,
  recipeCsvFile,
  recipeJsonFile,
  recipeMetaJsonFile,
  rootDir,
  sharedDataFiles,
} from '../utils/config.js'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../utils/csv.js'
import { applyRecipeScores } from '../utils/score.js'
import { readVideoStats } from '../utils/video-stats.js'

// 正则表达式预编译,避免每次调用时重新编译
const DATE_SLASH_REGEX = /\//g
const LAST_DB_UPDATED_REGEX = /export const lastDbUpdated = '.+'/
const CHUNK_FILE_REGEX = /^recipe-chunk-\d+\.json$/

/**
 * 清理旧的分片文件
 */
function cleanChunkFiles() {
  const dataDir = path.dirname(recipeJsonFile)
  const files = fs.readdirSync(dataDir)
  for (const file of files) {
    if (CHUNK_FILE_REGEX.test(file)) {
      fs.unlinkSync(path.join(dataDir, file))
    }
  }
}

/**
 * 计算内容的短 hash（MD5 前 8 位）
 */
function shortHash(content: string): string {
  return crypto.createHash('md5').update(content).digest('hex').slice(0, 8)
}

/**
 * 转换 recipe CSV → JSON（支持分片输出），返回菜谱和要写进 recipe-meta.json 的分片信息
 */
function convertRecipes() {
  consola.info('Converting recipe data...')

  const csvData = fs.readFileSync(recipeCsvFile, 'utf-8')
  // 质量分在这里算好写进 JSON，Web 和 iOS 只读 score 字段
  const recipes = applyRecipeScores(parseRecipeCsv(csvData), readVideoStats())

  if (recipes.length === 0) {
    consola.warn('No recipes parsed, skipping JSON generation')
    return
  }

  const dataDir = path.dirname(recipeJsonFile)

  if (recipes.length > CHUNK_THRESHOLD) {
    // 分片模式：删除旧文件，按 CHUNK_SIZE 分片写入
    cleanChunkFiles()
    if (fs.existsSync(recipeJsonFile)) {
      fs.unlinkSync(recipeJsonFile)
    }

    const chunks: { index: number, hash: string, count: number, ids: number[] }[] = []
    for (let i = 0; i < recipes.length; i += CHUNK_SIZE) {
      const chunkIndex = Math.floor(i / CHUNK_SIZE)
      const chunk = recipes.slice(i, i + CHUNK_SIZE)
      const content = JSON.stringify(chunk)
      const chunkFile = path.join(dataDir, `recipe-chunk-${chunkIndex}.json`)
      fs.writeFileSync(chunkFile, content)
      chunks.push({ index: chunkIndex, hash: shortHash(content), count: chunk.length, ids: chunk.map((r, j) => r.id ?? i + j) })
      consola.success(`Generated: ${chunkFile} (${chunk.length} recipes)`)
    }

    return { recipes, meta: { chunked: true, total: recipes.length, chunkSize: CHUNK_SIZE, chunks } }
  }
  else {
    // 单文件模式：保持原有行为，同时生成 meta
    cleanChunkFiles()
    fs.writeFileSync(recipeJsonFile, JSON.stringify(recipes))
    consola.success(`Generated: ${recipeJsonFile} (${recipes.length} recipes)`)
    return { recipes, meta: { chunked: false, total: recipes.length } }
  }
}

/**
 * 数据内容的版本：菜谱、相克规则、食材词典、常备调料和别名的 hash，内容不变则不变。
 * iOS 按它判断 CDN 上的数据是否比内置的新。
 */
export function computeDataVersion(recipes: unknown, rules: unknown, sharedFiles: string[] = sharedDataFiles): string {
  const hash = crypto.createHash('sha256')
  hash.update(JSON.stringify(recipes))
  hash.update(JSON.stringify(rules))
  for (const file of sharedFiles)
    hash.update(JSON.stringify(JSON.parse(fs.readFileSync(file, 'utf-8'))))
  return hash.digest('hex').slice(0, 12)
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
    return rules
  }
  catch (error) {
    consola.error('Failed to convert incompatible foods data:', error)
  }
}

/**
 * 更新 lastDbUpdated 时间戳
 */
function updateLastDbUpdated() {
  const constantsFile = path.join(rootDir, 'apps/web/app/constants/index.ts')

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
  const converted = convertRecipes()
  const rules = convertIncompatibleFoods() ?? []
  if (converted) {
    const meta = {
      schemaVersion: DATA_SCHEMA_VERSION,
      dataVersion: computeDataVersion(converted.recipes, rules),
      ...converted.meta,
      version: Date.now(),
    }
    fs.writeFileSync(recipeMetaJsonFile, JSON.stringify(meta, null, 2))
    consola.success(`Generated: ${recipeMetaJsonFile} (dataVersion ${meta.dataVersion})`)
  }
  // Production generation must not rewrite a tracked source timestamp after checkout.
  // Manual conversion keeps the existing maintenance timestamp behavior.
  if (process.env.COOK_PRESERVE_SOURCE_TIMESTAMP !== 'true')
    updateLastDbUpdated()
}
