import type { RecipeItem } from '../types/index.js'
import fs from 'node:fs'
import consola from 'consola'
import {
  RECIPE_CSV_HEADERS,
  recipeCsvFile,
  recipeJsonFile,
  SEP,
} from '../utils/config.js'
import { cleanBv, recipesToCsv } from '../utils/csv.js'
import {
  createFeishuClient,
  getFeishuCredentials,
  getSpreadsheetToken,
  readSpreadsheetValues,
} from '../utils/feishu.js'

/**
 * 安全获取单元格值，处理 null / undefined
 */
function cellStr(value: unknown): string {
  if (value == null)
    return ''
  return String(value).trim()
}

/**
 * 校验表头是否符合预期
 */
function validateHeaders(headers: unknown[]): void {
  const expected = RECIPE_CSV_HEADERS.split(',').filter(Boolean)
  const actual = headers.map(h => cellStr(h))

  for (let i = 0; i < expected.length; i++) {
    if (actual[i] !== expected[i]) {
      consola.warn(`表头不匹配: 期望 "${expected[i]}", 实际 "${actual[i]}" (列 ${i + 1})`)
      throw new Error('飞书表格表头与预期不匹配，请检查表格结构')
    }
  }
}

/**
 * 将飞书表格行数据转为 RecipeItem
 */
function rowToRecipeItem(row: unknown[]): RecipeItem | null {
  const name = cellStr(row[0])
  if (!name)
    return null

  const stuff = cellStr(row[1]).split(SEP).filter(Boolean)
  const bv = cleanBv(cellStr(row[2]))
  const difficulty = cellStr(row[3]) as RecipeItem['difficulty']
  const tags = cellStr(row[4]).split(SEP).filter(Boolean)
  const methods = cellStr(row[5]).split(SEP).filter(Boolean) as RecipeItem['methods']
  const tools = cellStr(row[6]).split(SEP).filter(Boolean)

  return {
    name,
    stuff,
    bv: bv || undefined,
    difficulty: difficulty || undefined,
    tags: tags.length > 0 ? tags : [],
    methods: methods && methods.length > 0 ? methods : [],
    tools,
  }
}

/**
 * fetch 命令入口
 */
export async function runFetch() {
  consola.start('从飞书拉取菜谱数据...')

  // 1. 校验环境变量 & 创建 Client
  const credentials = getFeishuCredentials()
  const client = createFeishuClient(credentials)

  // 2. 获取 spreadsheetToken
  consola.info('获取 Wiki 节点信息...')
  const spreadsheetToken = await getSpreadsheetToken(client)
  consola.info(`SpreadsheetToken: ${spreadsheetToken}`)

  // 3. 读取表格数据
  consola.info('读取电子表格数据...')
  const values = await readSpreadsheetValues(client, spreadsheetToken)

  // 4. 校验表头
  const headerRow = values[0]
  if (!headerRow) {
    throw new Error('表格无表头行')
  }
  validateHeaders(headerRow)
  consola.success('表头校验通过')

  // 5. 转换数据
  const recipes: RecipeItem[] = []
  for (let i = 1; i < values.length; i++) {
    const row = values[i]
    if (!row)
      continue

    const item = rowToRecipeItem(row)
    if (item) {
      recipes.push(item)
    }
    else {
      consola.warn(`跳过空行: 第 ${i + 1} 行`)
    }
  }

  consola.info(`解析到 ${recipes.length} 条菜谱数据`)

  // 6. 写入 CSV
  const csvContent = recipesToCsv(recipes)
  fs.writeFileSync(recipeCsvFile, csvContent, 'utf-8')
  consola.success(`写入 CSV: ${recipeCsvFile}`)

  // 7. 写入 JSON
  fs.writeFileSync(recipeJsonFile, JSON.stringify(recipes), 'utf-8')
  consola.success(`写入 JSON: ${recipeJsonFile}`)

  consola.success(`完成！共 ${recipes.length} 条菜谱`)
}
