import type { IncompatibleRule, RecipeItem } from '../types/index.js'
import consola from 'consola'
import Papa from 'papaparse'
import {
  INCOMPATIBLE_FOODS_CSV_HEADERS,
  RECIPE_CSV_HEADERS,
  SEP,
} from './config.js'

const LINE_BREAK_RE = /\r?\n/

/**
 * 清洗 BV 号：去除可能的 bilibili URL 前缀
 */
export function cleanBv(bv: string | undefined | null): string | undefined {
  if (!bv)
    return undefined
  const cleaned = bv.trim().replace('https://www.bilibili.com/video/', '')
  return cleaned || undefined
}

/**
 * 解析 recipe CSV 内容为 RecipeItem[]
 */
export function parseRecipeCsv(csvData: string): RecipeItem[] {
  const parsed = Papa.parse<string[]>(csvData, {
    skipEmptyLines: true,
  })

  if (parsed.errors.length > 0) {
    consola.warn('CSV 解析警告:', parsed.errors)
  }

  const rows = parsed.data
  if (rows.length < 2) {
    throw new Error('No data in csv file')
  }

  // 验证表头
  const headerRow = rows[0]?.join(',')
  if (headerRow !== RECIPE_CSV_HEADERS) {
    consola.warn(`Headers Changed: ${headerRow}`)
    return []
  }

  const recipes: RecipeItem[] = []

  // 从第二行开始处理数据
  for (let i = 1; i < rows.length; i++) {
    const row = rows[i]
    if (!row || row.length < 7) {
      consola.warn(`Invalid row ${i + 1}: ${row?.join(',')}`)
      continue
    }

    const name = row[0]?.trim() || ''
    if (!name)
      continue

    const stuff = row[1]?.trim().split(SEP).filter(Boolean) || []
    const bv = cleanBv(row[2])
    const difficulty = row[3]?.trim() as RecipeItem['difficulty']
    const tags = row[4] ? row[4].trim().split(SEP).filter(Boolean) : []
    const methods = row[5] ? (row[5].trim().split(SEP).filter(Boolean)) as RecipeItem['methods'] : []
    const tools = row[6] ? row[6].trim().split(SEP).filter(Boolean) : []
    const id = parseRecipeId(row[7])

    recipes.push({
      ...(id === undefined ? {} : { id }),
      name,
      stuff,
      bv: bv || undefined,
      difficulty: difficulty || undefined,
      tags: tags.length > 0 ? tags : [],
      methods: methods.length > 0 ? methods : [],
      tools,
    })
  }

  return recipes
}

/**
 * 解析 incompatible-foods CSV 内容为 IncompatibleRule[]
 */
export function parseIncompatibleFoodsCsv(csvData: string): IncompatibleRule[] {
  const lines = csvData.split(LINE_BREAK_RE)

  if (lines.length < 2) {
    throw new Error('No data in incompatible foods csv file')
  }

  if (lines[0]?.trim() !== INCOMPATIBLE_FOODS_CSV_HEADERS) {
    consola.warn(`Headers Changed: ${lines[0]}`)
    return []
  }

  const rules: IncompatibleRule[] = []

  lines.slice(1).forEach((line) => {
    if (line.trim()) {
      const attrs = line.split(',')
      if (attrs.length < 3) {
        consola.warn(`Invalid line: ${line}`)
        return
      }

      const foodA = attrs[0]?.trim()
      const foodB = attrs[1]?.trim()
      const reason = attrs[2]?.trim()

      if (!foodA || !foodB || !reason) {
        consola.warn(`Missing required field(s) in line: ${line}`)
        return
      }

      rules.push({ foodA, foodB, reason })
    }
  })

  return rules
}

/**
 * 将 RecipeItem[] 转为 CSV 字符串
 */
/**
 * 飞书表格没有 id 列：按「菜名 + 视频」沿用本地 CSV 中的 id，新菜谱接在最大 id 之后
 */
export function assignRecipeIds(recipes: RecipeItem[], existing: RecipeItem[]): RecipeItem[] {
  const key = (r: RecipeItem) => `${r.name}\u0000${r.bv ?? ''}`
  const known = new Map<string, number[]>()
  for (const r of existing) {
    if (r.id !== undefined)
      known.set(key(r), [...(known.get(key(r)) ?? []), r.id])
  }
  let next = Math.max(-1, ...existing.map(r => r.id ?? -1)) + 1
  return recipes.map((r) => {
    const id = known.get(key(r))?.shift() ?? next++
    return { ...r, id }
  })
}

/** id 列必须是非负整数，否则视为缺失 */
export function parseRecipeId(value: string | undefined): number | undefined {
  const text = value?.trim() ?? ''
  return /^\d+$/.test(text) ? Number(text) : undefined
}

export function recipesToCsv(recipes: RecipeItem[]): string {
  const headers = RECIPE_CSV_HEADERS.split(',')

  const rows = recipes.map(item => [
    item.name,
    item.stuff.join(SEP),
    item.bv || '',
    item.difficulty || '',
    item.tags?.join(SEP) || '',
    item.methods?.join(SEP) || '',
    item.tools?.join(SEP) || '',
    item.id ?? '',
  ])

  const csv = Papa.unparse({
    fields: headers,
    data: rows,
  })

  return csv
}
