import type { RecipeItem } from '~/types'

/**
 * Favorites and browsing history, plus the tombstones needed to sync deletions.
 *
 * Merging is a pure, commutative and idempotent function, so local data, cloud
 * data and imported files can be combined in any order and any number of times.
 */

export const USER_DATA_VERSION = 1
export const HISTORY_LIMIT = 200
export const TOMBSTONE_LIMIT = 500
export const TOMBSTONE_TTL = 180 * 24 * 60 * 60 * 1000

export interface FavoriteEntry { id: number, time: number }
export interface FavoriteRemoval { id: number, time: number }
export interface HistoryEntry { recipe: RecipeItem, time: number }
/** Identifies one removed history record by its recipe name and visit time. */
export interface HistoryRemoval { name: string, time: number }

export interface UserData {
  favorites: FavoriteEntry[]
  favoriteRemovals: FavoriteRemoval[]
  histories: HistoryEntry[]
  historyRemovals: HistoryRemoval[]
  /** Records visited at or before this time were cleared. */
  historyClearedAt: number
}

export interface UserDataExportFile extends UserData {
  app: 'cook'
  version: typeof USER_DATA_VERSION
  exportedAt: number
}

export function createEmptyUserData(): UserData {
  return {
    favorites: [],
    favoriteRemovals: [],
    histories: [],
    historyRemovals: [],
    historyClearedAt: 0,
  }
}

export function mergeUserData(a: UserData, b: UserData, now = Date.now()): UserData {
  const historyClearedAt = Math.max(a.historyClearedAt, b.historyClearedAt)
  const { favorites, favoriteRemovals } = mergeFavorites(
    [...a.favorites, ...b.favorites],
    [...a.favoriteRemovals, ...b.favoriteRemovals],
    now,
  )
  const { histories, historyRemovals } = mergeHistories(
    [...a.histories, ...b.histories],
    [...a.historyRemovals, ...b.historyRemovals],
    historyClearedAt,
    now,
  )
  return { favorites, favoriteRemovals, histories, historyRemovals, historyClearedAt }
}

export function isSameUserData(a: UserData, b: UserData): boolean {
  return JSON.stringify(toPlain(a)) === JSON.stringify(toPlain(b))
}

function mergeFavorites(
  adds: FavoriteEntry[],
  removals: FavoriteRemoval[],
  now: number,
): Pick<UserData, 'favorites' | 'favoriteRemovals'> {
  const latestAdd = latestBy(adds, entry => entry.id)
  const latestRemoval = latestBy(removals, entry => entry.id)

  // Last write wins per recipe id; a removal at the same instant wins.
  const favorites = [...latestAdd.values()]
    .filter(add => (latestRemoval.get(add.id)?.time ?? -1) < add.time)
    .map(({ id, time }) => ({ id, time }))
    .sort((x, y) => x.time - y.time || x.id - y.id)
  const favoriteRemovals = pruneTombstones(
    [...latestRemoval.values()].filter(removal => (latestAdd.get(removal.id)?.time ?? -1) <= removal.time),
    now,
  )
    .map(({ id, time }) => ({ id, time }))
    .sort((x, y) => x.time - y.time || x.id - y.id)

  return { favorites, favoriteRemovals }
}

function mergeHistories(
  entries: HistoryEntry[],
  removals: HistoryRemoval[],
  clearedAt: number,
  now: number,
): Pick<UserData, 'histories' | 'historyRemovals'> {
  const historyRemovals = pruneTombstones(
    uniqueBy(removals.filter(removal => removal.time > clearedAt), removal => historyKey(removal.name, removal.time)),
    now,
  )
    .map(({ name, time }) => ({ name, time }))
    .sort((x, y) => x.time - y.time || x.name.localeCompare(y.name))
  const removed = new Set(historyRemovals.map(removal => historyKey(removal.name, removal.time)))

  // Oldest first, matching how the app appends visits. Recipes are rebuilt in a
  // canonical shape so local and cloud copies of the same visit compare equal.
  const histories = uniqueBy(
    entries.filter(entry => entry.time > clearedAt && !removed.has(historyKey(entry.recipe.name, entry.time))),
    entry => historyKey(entry.recipe.name, entry.time),
  )
    .map(entry => ({ recipe: readRecipe(entry.recipe) ?? entry.recipe, time: entry.time }))
    .sort((x, y) => x.time - y.time || x.recipe.name.localeCompare(y.recipe.name))
    .slice(-HISTORY_LIMIT)

  return { histories, historyRemovals }
}

function historyKey(name: string, time: number): string {
  return `${name}\u0000${time}`
}

function latestBy<T extends { time: number }>(list: T[], key: (item: T) => number): Map<number, T> {
  const map = new Map<number, T>()
  for (const item of list) {
    const current = map.get(key(item))
    if (!current || item.time > current.time)
      map.set(key(item), item)
  }
  return map
}

function uniqueBy<T>(list: T[], key: (item: T) => string): T[] {
  const map = new Map<string, T>()
  for (const item of list) {
    if (!map.has(key(item)))
      map.set(key(item), item)
  }
  return [...map.values()]
}

function pruneTombstones<T extends { time: number }>(list: T[], now: number): T[] {
  return list
    .filter(item => item.time >= now - TOMBSTONE_TTL)
    .sort((x, y) => y.time - x.time)
    .slice(0, TOMBSTONE_LIMIT)
}

function toPlain(data: UserData): UserData {
  return {
    favorites: data.favorites.map(({ id, time }) => ({ id, time })),
    favoriteRemovals: data.favoriteRemovals.map(({ id, time }) => ({ id, time })),
    histories: data.histories.map(({ recipe, time }) => ({ recipe, time })),
    historyRemovals: data.historyRemovals.map(({ name, time }) => ({ name, time })),
    historyClearedAt: data.historyClearedAt,
  }
}

/**
 * Validate untrusted data (a cloud document or an imported file).
 * Returns null when the shape is not recognisable; invalid entries are dropped.
 */
export function normalizeUserData(value: unknown): UserData | null {
  if (!isRecord(value))
    return null
  if (value.version !== undefined && value.version !== USER_DATA_VERSION)
    return null

  return {
    favorites: readList(value.favorites, readFavorite),
    favoriteRemovals: readList(value.favoriteRemovals, readFavorite),
    histories: readList(value.histories, readHistory),
    historyRemovals: readList(value.historyRemovals, readHistoryRemoval),
    historyClearedAt: isTime(value.historyClearedAt) ? value.historyClearedAt : 0,
  }
}

export function createUserDataExport(data: UserData, now = Date.now()): UserDataExportFile {
  return {
    app: 'cook',
    version: USER_DATA_VERSION,
    exportedAt: now,
    ...toPlain(data),
  }
}

export function parseUserDataExport(text: string): UserData | null {
  let value: unknown
  try {
    value = JSON.parse(text)
  }
  catch {
    return null
  }
  if (!isRecord(value) || value.app !== 'cook' || value.version !== USER_DATA_VERSION)
    return null
  return normalizeUserData(value)
}

function readList<T>(value: unknown, read: (item: unknown) => T | null): T[] {
  if (!Array.isArray(value))
    return []
  return value.map(read).filter((item): item is T => item !== null)
}

function readFavorite(value: unknown): FavoriteEntry | null {
  if (!isRecord(value) || !Number.isInteger(value.id) || (value.id as number) < 0 || !isTime(value.time))
    return null
  return { id: value.id as number, time: value.time }
}

function readHistoryRemoval(value: unknown): HistoryRemoval | null {
  if (!isRecord(value) || !isName(value.name) || !isTime(value.time))
    return null
  return { name: value.name, time: value.time }
}

function readHistory(value: unknown): HistoryEntry | null {
  if (!isRecord(value) || !isTime(value.time))
    return null
  const recipe = readRecipe(value.recipe)
  return recipe ? { recipe, time: value.time } : null
}

function readRecipe(value: unknown): RecipeItem | null {
  if (!isRecord(value) || !isName(value.name))
    return null

  const recipe: RecipeItem = {
    name: value.name,
    stuff: readStrings(value.stuff),
    tools: readStrings(value.tools),
  }
  for (const key of ['link', 'bv', 'difficulty'] as const) {
    if (typeof value[key] === 'string')
      (recipe as unknown as Record<string, unknown>)[key] = value[key]
  }
  for (const key of ['emojis', 'tags', 'methods'] as const) {
    if (Array.isArray(value[key]))
      (recipe as unknown as Record<string, unknown>)[key] = readStrings(value[key])
  }
  return recipe
}

function readStrings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function isName(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0 && value.length <= 200
}

function isTime(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
