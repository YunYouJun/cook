import type { CustomRecipe } from '~/utils/customRecipe'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'

export type { CustomRecipe }

// 和收藏、历史一样始终保存在本机；不参与云同步
export const customRecipes = useStorage<CustomRecipe[]>(`${namespace}:custom-recipes`, [])

export function getCustomRecipe(customId: string) {
  return customRecipes.value.find(recipe => recipe.customId === customId)
}

export function saveCustomRecipe(recipe: CustomRecipe) {
  const exists = customRecipes.value.some(item => item.customId === recipe.customId)
  customRecipes.value = exists
    ? customRecipes.value.map(item => item.customId === recipe.customId ? recipe : item)
    : [recipe, ...customRecipes.value]
}

export function removeCustomRecipe(customId: string) {
  customRecipes.value = customRecipes.value.filter(recipe => recipe.customId !== customId)
}
