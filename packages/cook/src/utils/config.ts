import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 基于当前文件位置计算目录：
// 开发态 config.ts → src/utils/ → packages/cook/ → 项目根
// 构建态 dist/cli.mjs → packages/cook/dist/（或 node_modules/@yunyoujun/cook/dist/）
const __dirname = path.dirname(fileURLToPath(import.meta.url))

function findMonorepoRoot(startDir: string): string | undefined {
  let current = startDir
  for (let i = 0; i < 6; i++) {
    if (fs.existsSync(path.join(current, 'packages/data/recipe.csv')))
      return current
    const parent = path.dirname(current)
    if (parent === current)
      break
    current = parent
  }
  return undefined
}

function resolveDataDir(startDir: string, monorepoRoot?: string): string {
  if (monorepoRoot)
    return path.join(monorepoRoot, 'packages/data')
  for (const candidate of [
    path.resolve(startDir, 'data'),
    path.resolve(startDir, '../data'),
    path.resolve(startDir, '../../data'),
  ]) {
    if (fs.existsSync(path.join(candidate, 'recipe.csv')))
      return candidate
  }
  return path.resolve(startDir, '../../../../packages/data')
}

const detectedMonorepoRoot = findMonorepoRoot(__dirname)
const root = detectedMonorepoRoot ?? path.resolve(__dirname, '../../../..')

// monorepo 根目录（独立安装运行时回退到包目录上游，仅 monorepo 命令使用）
export const rootDir = root

// 运行时数据目录：monorepo 内读取 packages/data，独立安装包读取内置 dist/data
export const dataDir = resolveDataDir(__dirname, detectedMonorepoRoot)

// .env 文件路径
export const envFile = path.resolve(root, '.env')

// Data 文件路径
export const recipeCsvFile = path.resolve(dataDir, 'recipe.csv')
export const recipeJsonFile = path.resolve(dataDir, 'recipe.json')
export const recipeMetaJsonFile = path.resolve(dataDir, 'recipe-meta.json')
export const incompatibleFoodsCsvFile = path.resolve(dataDir, 'incompatible-foods.csv')
export const incompatibleFoodsJsonFile = path.resolve(dataDir, 'incompatible-foods.json')
export const shelfLifeJsonFile = path.resolve(dataDir, 'shelf-life.json')
export const pantryJsonFile = path.resolve(dataDir, 'pantry.json')
export const foodJsonFile = path.resolve(dataDir, 'food.json')
export const aliasesJsonFile = path.resolve(dataDir, 'aliases.json')
/** 视频巡检记下的 B 站数据，用来算菜谱质量分（见 utils/score.ts） */
export const videoStatsJsonFile = path.resolve(dataDir, 'video-stats.json')
/** 手写、直接提交的共享数据，参与 dataVersion 计算 */
export const sharedDataFiles = ['aliases.json', 'food.json', 'pantry.json', 'query.json', 'shelf-life.json']
  .map(file => path.resolve(dataDir, file))

/**
 * packages/data 里 JSON 的结构版本，写进 recipe-meta.json 的 schemaVersion。
 * 字段改名、删除或改类型时加一，并同步更新 packages/data/schema 和 iOS 的 CookCore；只加可选字段不用改。
 */
export const DATA_SCHEMA_VERSION = 1

// 分片配置
export const CHUNK_SIZE = 200 // 每片菜谱数
export const CHUNK_THRESHOLD = 1000 // 超过此数量启用分片

// CSV Headers
// id 是菜谱的固定编号：收藏、反馈都按它记录，已有行的 id 不能修改或复用
export const RECIPE_CSV_HEADERS = 'name,stuff,bv,difficulty,tags,methods,tools,id,link'
/** 加 link 列之前的表头 */
export const LEGACY_RECIPE_CSV_HEADERS = 'name,stuff,bv,difficulty,tags,methods,tools,id'
/** 飞书表格只有前 7 列，没有 id */
export const FEISHU_RECIPE_HEADERS = 'name,stuff,bv,difficulty,tags,methods,tools'
export const INCOMPATIBLE_FOODS_CSV_HEADERS = 'foodA,foodB,reason'

// 分隔符（食材、标签等使用中文顿号）
export const SEP = '、'

// 飞书相关配置
export const FEISHU_WIKI_NODE_TOKEN = 'KgxowvnB9iM91AkZEJHcNGoQnPg'
export const FEISHU_SHEET_ID = 'X4j9Bn'
