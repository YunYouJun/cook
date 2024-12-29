import type { RecipeItem } from '~/types'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'

export interface RecipeHistoryItem {
  recipe: RecipeItem
  time: number
}

export const recipeHistories = useStorage<RecipeHistoryItem[]>(`${namespace}:history`, [])
