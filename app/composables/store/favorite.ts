import type { RecipeItem } from '~/types'
import type { DbRecipeItem } from '~/utils/db'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'

export interface FavoriteEntry { id: number, time: number }

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
  if (idx >= 0)
    list.splice(idx, 1)
  else
    list.push({ id, time: Date.now() })
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
