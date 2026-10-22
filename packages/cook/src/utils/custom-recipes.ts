/** Portable private recipes. This is deliberately separate from favorites/history sync v1. */
export interface PrivateRecipe {
  customId: string
  name: string
  stuff: string[]
  tools: string[]
  bv?: string
  link?: string
  note?: string
  tags?: string[]
  methods?: string[]
  difficulty?: '简单' | '普通' | '困难' | ''
  createdAt: number
  updatedAt: number
  /** Original identity when an import conflict was explicitly saved as a copy. */
  copiedFrom?: string
}

export interface PrivateRecipeFile {
  app: 'cook'
  kind: 'custom-recipes'
  version: 1
  exportedAt: number
  recipes: PrivateRecipe[]
}

export const PRIVATE_RECIPE_FILE_LIMIT = 5 * 1024 * 1024
export const PRIVATE_RECIPE_LIMIT = 5000
const BV = /^BV[0-9a-z]{10}$/i
const ID = /^[\w:-]{1,160}$/
const RECIPE_FIELDS = new Set(['customId', 'name', 'stuff', 'tools', 'bv', 'link', 'note', 'tags', 'methods', 'difficulty', 'createdAt', 'updatedAt', 'copiedFrom'])

export class PrivateRecipeFileError extends Error {
  constructor(message = '无法识别这份私人菜谱备份，原有菜谱未改变。') {
    super(message)
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function string(value: unknown, max: number): value is string {
  return typeof value === 'string' && value.length <= max
}

function strings(value: unknown): value is string[] {
  return Array.isArray(value) && value.length <= 64
    && value.every(item => string(item, 100) && !!item.trim())
}

export function isPrivateRecipeURL(value: string): boolean {
  if (value.length > 4096 || /\s/.test(value) || [...value].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127))
    return false
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) && !!url.hostname && !url.username && !url.password
  }
  catch {
    return false
  }
}

export function validatePrivateRecipe(value: unknown): PrivateRecipe {
  if (!record(value)
    || Object.keys(value).some(key => !RECIPE_FIELDS.has(key))
    || !string(value.customId, 160) || !ID.test(value.customId)
    || !string(value.name, 30) || !value.name.trim()
    || !strings(value.stuff) || !value.stuff.length || !strings(value.tools)
    || !Number.isSafeInteger(value.createdAt) || Number(value.createdAt) < 0
    || !Number.isSafeInteger(value.updatedAt) || Number(value.updatedAt) < Number(value.createdAt)) {
    throw new PrivateRecipeFileError()
  }
  const result: PrivateRecipe = {
    customId: value.customId,
    name: value.name,
    stuff: [...value.stuff],
    tools: [...value.tools],
    createdAt: value.createdAt as number,
    updatedAt: value.updatedAt as number,
  }
  for (const key of ['bv', 'link', 'note', 'difficulty', 'copiedFrom'] as const) {
    const text = value[key]
    if (text === undefined)
      continue
    if (!string(text, key === 'note' ? 20000 : key === 'link' ? 4096 : 160)
      || (key === 'bv' && !!text && !BV.test(text))
      || (key === 'link' && !!text && !isPrivateRecipeURL(text))
      || (key === 'copiedFrom' && !ID.test(text))) {
      throw new PrivateRecipeFileError()
    }
    if (key === 'difficulty') {
      if (!['', '简单', '普通', '困难'].includes(text))
        throw new PrivateRecipeFileError()
      result.difficulty = text as PrivateRecipe['difficulty']
    }
    else {
      result[key] = text
    }
  }
  for (const key of ['tags', 'methods'] as const) {
    if (value[key] !== undefined) {
      if (!strings(value[key]))
        throw new PrivateRecipeFileError()
      result[key] = [...value[key]]
    }
  }
  return result
}

/** Timestamps and identity do not turn otherwise identical content into a conflict. */
export function privateRecipeContent(recipe: PrivateRecipe): string {
  return JSON.stringify([
    recipe.name,
    recipe.stuff,
    recipe.tools,
    recipe.bv || '',
    recipe.link || '',
    recipe.note || '',
    recipe.tags || [],
    recipe.methods || [],
    recipe.difficulty || '',
  ])
}

export function parsePrivateRecipeFile(text: string): PrivateRecipeFile {
  if (new TextEncoder().encode(text).length > PRIVATE_RECIPE_FILE_LIMIT)
    throw new PrivateRecipeFileError('文件超过 5 MB，原有菜谱未改变。')
  let value: unknown
  try {
    value = JSON.parse(text)
  }
  catch { throw new PrivateRecipeFileError() }
  if (!record(value) || value.app !== 'cook' || value.kind !== 'custom-recipes' || value.version !== 1
    || !Number.isSafeInteger(value.exportedAt) || Number(value.exportedAt) < 0
    || !Array.isArray(value.recipes) || value.recipes.length > PRIVATE_RECIPE_LIMIT) {
    throw new PrivateRecipeFileError()
  }
  const recipes = value.recipes.map(validatePrivateRecipe)
  const byId = new Map<string, PrivateRecipe>()
  for (const recipe of recipes) {
    const previous = byId.get(recipe.customId)
    if (previous && JSON.stringify(previous) !== JSON.stringify(recipe))
      throw new PrivateRecipeFileError('文件里同一个编号有不同内容，请先修复备份。原有菜谱未改变。')
    byId.set(recipe.customId, recipe)
  }
  return { app: 'cook', kind: 'custom-recipes', version: 1, exportedAt: value.exportedAt as number, recipes: [...byId.values()] }
}

export function exportPrivateRecipes(recipes: PrivateRecipe[], now = Date.now()): string {
  const text = JSON.stringify({ app: 'cook', kind: 'custom-recipes', version: 1, exportedAt: now, recipes: recipes.map(validatePrivateRecipe) }, null, 2)
  parsePrivateRecipeFile(text)
  return text
}

export function previewPrivateRecipeImport(current: PrivateRecipe[], incoming: PrivateRecipe[], deletedIds: string[] = []) {
  const additions: PrivateRecipe[] = []
  const conflicts: PrivateRecipe[] = []
  const duplicates: PrivateRecipe[] = []
  for (const recipe of incoming) {
    const previous = current.find(item => item.customId === recipe.customId)
    const content = privateRecipeContent(recipe)
    const copied = current.some(item => item.copiedFrom === recipe.customId && privateRecipeContent(item) === content)
    if (copied || (previous && privateRecipeContent(previous) === content))
      duplicates.push(recipe)
    else if (previous || deletedIds.includes(recipe.customId))
      conflicts.push(recipe)
    else
      additions.push(recipe)
  }
  return { additions, conflicts, duplicates }
}

/** Never overwrites an existing record. Call again against fresh state at confirmation. */
export function mergePrivateRecipeImport(
  current: PrivateRecipe[],
  incoming: PrivateRecipe[],
  keepCopies: boolean,
  createId: () => string,
  deletedIds: string[] = [],
): PrivateRecipe[] {
  const preview = previewPrivateRecipeImport(current, incoming, deletedIds)
  const result = [...current, ...preview.additions]
  if (keepCopies) {
    for (const recipe of preview.conflicts) {
      const customId = createId()
      if (!ID.test(customId) || result.some(item => item.customId === customId) || deletedIds.includes(customId))
        throw new PrivateRecipeFileError('生成副本编号失败，请重试。原有菜谱未改变。')
      result.push({ ...recipe, customId, copiedFrom: recipe.customId })
    }
  }
  if (result.length > PRIVATE_RECIPE_LIMIT)
    throw new PrivateRecipeFileError('合并后超过 5000 道菜谱，原有菜谱未改变。')
  return result
}
