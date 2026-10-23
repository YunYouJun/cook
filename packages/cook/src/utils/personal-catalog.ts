export const CATALOG_CATEGORIES = ['vegetable', 'meat', 'staple', 'tools'] as const
export type CatalogCategory = typeof CATALOG_CATEGORIES[number]
export interface CatalogEntry { category: CatalogCategory, name: string }
export interface PersonalCatalog {
  app: 'cook'
  kind: 'personal-catalog'
  version: 1
  items: CatalogEntry[]
  hidden: CatalogEntry[]
}
export const CATALOG_FILE_LIMIT = 256 * 1024
export const emptyPersonalCatalog = (): PersonalCatalog => ({ app: 'cook', kind: 'personal-catalog', version: 1, items: [], hidden: [] })
export const catalogKey = (entry: CatalogEntry) => `${entry.category === 'tools' ? 'tool' : 'stuff'}:${entry.name}`

export function parsePersonalCatalog(text: string): PersonalCatalog {
  if (new TextEncoder().encode(text).length > CATALOG_FILE_LIMIT)
    throw new Error('食材和厨具文件不能超过 256 KB。')
  const value = JSON.parse(text)
  if (!value || Object.keys(value).some(key => !['app', 'kind', 'version', 'items', 'hidden'].includes(key)) || value.app !== 'cook' || value.kind !== 'personal-catalog' || value.version !== 1)
    throw new Error('无法识别这份食材和厨具备份，或备份版本过新。')
  for (const field of ['items', 'hidden'] as const) {
    const entries = value[field]
    if (!Array.isArray(entries) || entries.length > 500)
      throw new Error('每份配置最多包含 500 个自定义条目和 500 个隐藏条目。')
    const seen = new Set<string>()
    for (const entry of entries) {
      if (!entry || Object.keys(entry).some(key => !['category', 'name'].includes(key)) || !CATALOG_CATEGORIES.includes(entry.category) || typeof entry.name !== 'string'
        || !entry.name.trim() || entry.name !== entry.name.trim() || entry.name.length > 30 || [...entry.name].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127)) {
        throw new Error('名称需为 1–30 个字符，不能包含换行或控制字符。')
      }
      const key = catalogKey(entry)
      if (seen.has(key))
        throw new Error('备份包含重复的食材或厨具。')
      seen.add(key)
    }
  }
  return { ...emptyPersonalCatalog(), items: value.items.map(({ category, name }: CatalogEntry) => ({ category, name })), hidden: value.hidden.map(({ category, name }: CatalogEntry) => ({ category, name })) }
}

export function exportPersonalCatalog(value: PersonalCatalog): string {
  const text = JSON.stringify(value, null, 2)
  parsePersonalCatalog(text)
  return text
}

/** Import adds entries and hidden preferences; existing categories and recipes are preserved. */
export function mergePersonalCatalog(current: PersonalCatalog, incoming: PersonalCatalog): PersonalCatalog {
  const merge = (a: CatalogEntry[], b: CatalogEntry[]) => [...a, ...b.filter(item => !a.some(old => catalogKey(old) === catalogKey(item)))]
  const next = { ...current, items: merge(current.items, incoming.items), hidden: merge(current.hidden, incoming.hidden) }
  return parsePersonalCatalog(exportPersonalCatalog(next))
}

export function catalogItems<T extends { name: string }>(base: T[], personal: PersonalCatalog, category: CatalogCategory, make: (name: string) => T, includeHidden = false): T[] {
  return [
    ...base.filter(item => includeHidden || !personal.hidden.some(hidden => hidden.category === category && hidden.name === item.name)),
    ...personal.items.filter(item => item.category === category && !base.some(old => old.name === item.name)).map(item => make(item.name)),
  ]
}
