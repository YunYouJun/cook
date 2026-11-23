import type { RecipeItem } from '~/types'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'
import { clearUserDataHistory } from '~/utils/userData'
import { userDataMeta } from './userDataMeta'

export interface RecipeHistoryItem {
  recipe: RecipeItem
  time: number
}

export const recipeHistories = useStorage<RecipeHistoryItem[]>(`${namespace}:history`, [])

export function addRecipeHistory(recipe: RecipeItem) {
  const { historyClearedAt, historyRemovals } = userDataMeta.value
  const latestVisit = recipeHistories.value.reduce((latest, visit) => {
    return visit.recipe.name === recipe.name ? Math.max(latest, visit.time) : latest
  }, historyClearedAt)
  const latestRemoval = historyRemovals.reduce((latest, removal) => {
    return removal.name === recipe.name ? Math.max(latest, removal.time) : latest
  }, latestVisit)
  recipeHistories.value.push({ recipe, time: Math.max(Date.now(), latestRemoval + 1) })
}

export function removeRecipeHistory(history: RecipeHistoryItem) {
  recipeHistories.value = recipeHistories.value.filter(h => h !== history)
  userDataMeta.value = {
    ...userDataMeta.value,
    historyRemovals: [
      ...userDataMeta.value.historyRemovals,
      { name: history.recipe.name, time: history.time },
    ],
  }
}

export function clearRecipeHistories() {
  const cleared = clearUserDataHistory({ ...userDataMeta.value, histories: recipeHistories.value })
  userDataMeta.value = {
    ...userDataMeta.value,
    historyClearedAt: cleared.historyClearedAt,
    historyRemovals: cleared.historyRemovals,
  }
  recipeHistories.value = cleared.histories
}
