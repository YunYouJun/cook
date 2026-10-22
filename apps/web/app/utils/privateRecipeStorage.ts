import type { PrivateRecipe } from '@yunyoujun/cook/custom-recipes'
import { exportPrivateRecipes, validatePrivateRecipe } from '@yunyoujun/cook/custom-recipes'

/** Reads existing v1 Web arrays without changing their identifiers or optional fields. */
export function readPrivateRecipeStorage(storage: Pick<Storage, 'getItem'>, key: string): PrivateRecipe[] {
  const raw = storage.getItem(key)
  if (raw === null)
    return []
  const value: unknown = JSON.parse(raw)
  if (!Array.isArray(value))
    throw new Error('本机菜谱数据无法识别，请先保留原始数据。')
  value.forEach(validatePrivateRecipe)
  return value
}

/** Validate the entire mutation first; a failed write leaves the stored bytes untouched. */
export function updatePrivateRecipeStorage(storage: Pick<Storage, 'getItem' | 'setItem'>, key: string, update: (current: PrivateRecipe[]) => PrivateRecipe[]) {
  const current = readPrivateRecipeStorage(storage, key)
  const next = update(current)
  exportPrivateRecipes(next)
  storage.setItem(key, JSON.stringify(next))
  return next
}
