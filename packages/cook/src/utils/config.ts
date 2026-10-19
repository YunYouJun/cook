import path from 'node:path'
import { fileURLToPath } from 'node:url'

// 基于当前文件位置计算 monorepo 根目录
// config.ts → src/utils/ → packages/cook/ → 项目根
const __dirname = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(__dirname, '../../../..')

// monorepo 根目录
export const rootDir = root

// .env 文件路径
export const envFile = path.resolve(root, '.env')

// Data 文件路径
export const recipeCsvFile = path.resolve(root, 'packages/data/recipe.csv')
export const recipeJsonFile = path.resolve(root, 'packages/data/recipe.json')
export const recipeMetaJsonFile = path.resolve(root, 'packages/data/recipe-meta.json')
export const incompatibleFoodsCsvFile = path.resolve(root, 'packages/data/incompatible-foods.csv')
export const incompatibleFoodsJsonFile = path.resolve(root, 'packages/data/incompatible-foods.json')
/** 手写、直接提交的共享数据，参与 dataVersion 计算 */
export const sharedDataFiles = ['aliases.json', 'food.json', 'pantry.json']
  .map(file => path.resolve(root, 'packages/data', file))

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
export const RECIPE_CSV_HEADERS = 'name,stuff,bv,difficulty,tags,methods,tools,id'
/** 飞书表格只有前 7 列，没有 id */
export const FEISHU_RECIPE_HEADERS = 'name,stuff,bv,difficulty,tags,methods,tools'
export const INCOMPATIBLE_FOODS_CSV_HEADERS = 'foodA,foodB,reason'

// 分隔符（食材、标签等使用中文顿号）
export const SEP = '、'

// 飞书相关配置
export const FEISHU_WIKI_NODE_TOKEN = 'KgxowvnB9iM91AkZEJHcNGoQnPg'
export const FEISHU_SHEET_ID = 'X4j9Bn'
