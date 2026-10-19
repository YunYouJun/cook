import type { RecipeItem } from '~/types'
import type { DbRecipeItem } from '~/utils/db'
import type { FavoriteEntry } from '~/utils/userData'
import { useStorage } from '@vueuse/core'
import { computed } from 'vue'
import { namespace } from '~/constants'
import { userDataMeta } from './userDataMeta'

export type { FavoriteEntry }

// Store favorite entries with timestamp in localStorage
const rawFavorites = useStorage(`${namespace}:favorites`, [] as any)

// Migration: if old format number[] exists, convert to FavoriteEntry[] with current time
function ensureFavoriteEntries(): FavoriteEntry[] {
  const now = Date.now()
  const v = rawFavorites.value
  if (Array.isArray(v)) {
    if (v.length === 0)
      return []
    // old format: array of numbers
    if (typeof v[0] === 'number') {
      const migrated: FavoriteEntry[] = (v as number[]).map(id => ({ id, time: now }))
      rawFavorites.value = migrated
      return migrated
    }
    // new format
    if (typeof v[0] === 'object' && v[0] && 'id' in v[0])
      return v as FavoriteEntry[]
  }
  // fallback
  rawFavorites.value = []
  return []
}

export const favoriteEntries = computed<FavoriteEntry[]>(() => ensureFavoriteEntries())
export const favoriteRecipeIds = computed<number[]>(() => favoriteEntries.value.map(e => e.id))

function getId(item: RecipeItem | DbRecipeItem): number | null {
  // Only support DbRecipeItem with numeric id for now
  return typeof (item as DbRecipeItem).id === 'number' ? (item as DbRecipeItem).id! : null
}

export function isFavorited(item: RecipeItem | DbRecipeItem) {
  const id = getId(item)
  if (id == null)
    return false
  return favoriteRecipeIds.value.includes(id)
}

export function toggleFavorite(item: RecipeItem | DbRecipeItem) {
  const id = getId(item)
  if (id == null)
    return
  const list = ensureFavoriteEntries()
  const idx = list.findIndex(e => e.id === id)
  const time = Date.now()
  if (idx >= 0)
    list.splice(idx, 1)
  else
    list.push({ id, time })
  rawFavorites.value = list

  // Keep one tombstone per id so the removal can sync to other devices.
  const favoriteRemovals = userDataMeta.value.favoriteRemovals.filter(r => r.id !== id)
  if (idx >= 0)
    favoriteRemovals.push({ id, time })
  userDataMeta.value = { ...userDataMeta.value, favoriteRemovals }
}

export function setFavoriteEntries(list: FavoriteEntry[]) {
  rawFavorites.value = list
}

export function getFavoriteTime(item: RecipeItem | DbRecipeItem): number | null {
  const id = getId(item)
  if (id == null)
    return null
  const list = ensureFavoriteEntries()
  const entry = list.find(e => e.id === id)
  return entry?.time ?? null
}
