import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'
import type { RecipeItem } from '~/types'

export interface RecipeHistoryItem {
  recipe: RecipeItem
  time: number
}

export const recipeHistories = useStorage<RecipeHistoryItem[]>(`${namespace}:history`, [])
