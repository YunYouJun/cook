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
export const recipeCsvFile = path.resolve(root, 'app/data/recipe.csv')
export const recipeJsonFile = path.resolve(root, 'app/data/recipe.json')
export const incompatibleFoodsCsvFile = path.resolve(root, 'app/data/incompatible-foods.csv')
export const incompatibleFoodsJsonFile = path.resolve(root, 'app/data/incompatible-foods.json')

// CSV Headers
export const RECIPE_CSV_HEADERS = 'name,stuff,bv,difficulty,tags,methods,tools,'
export const INCOMPATIBLE_FOODS_CSV_HEADERS = 'foodA,foodB,reason'

// 分隔符（食材、标签等使用中文顿号）
export const SEP = '、'

// 飞书相关配置
export const FEISHU_WIKI_NODE_TOKEN = 'KgxowvnB9iM91AkZEJHcNGoQnPg'
export const FEISHU_SHEET_ID = 'X4j9Bn'
