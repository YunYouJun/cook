import shelfLifeData from '../../../data/shelf-life.json'
import { uniqueStuff } from './recommend.js'

export interface ShelfLifeData {
  default: number
  items: Record<string, number>
}

export interface ShelfLifeLookupItem {
  input: string
  normalized: string
  days: number
  isDefault: boolean
}

export interface ShelfLifeLookupResult {
  defaultDays: number
  items: ShelfLifeLookupItem[]
}

const DEFAULT_SHELF_LIFE = shelfLifeData as ShelfLifeData

/**
 * 查询食材在冰箱冷藏室的建议保质天数（支持别名标准化）。
 */
export function lookupShelfLife(
  inputs: string[],
  data: ShelfLifeData = DEFAULT_SHELF_LIFE,
): ShelfLifeLookupResult {
  const splitItems = inputs
    .flatMap(item => item.split(/[,，、;；\n]/))
    .map(item => item.trim())
    .filter(Boolean)

  const seen = new Set<string>()
  const items: ShelfLifeLookupItem[] = []

  for (const input of splitItems) {
    const normalized = uniqueStuff([input])[0] ?? input
    if (seen.has(normalized))
      continue
    seen.add(normalized)
    const exact = data.items[normalized]
    items.push({
      input,
      normalized,
      days: exact ?? data.default,
      isDefault: exact === undefined,
    })
  }

  return {
    defaultDays: data.default,
    items,
  }
}
