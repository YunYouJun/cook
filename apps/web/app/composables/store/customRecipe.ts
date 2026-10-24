import type { CustomRecipe } from '~/utils/customRecipe'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'
import { updatePrivateRecipeStorage } from '~/utils/privateRecipeStorage'

export type { CustomRecipe }

// 和收藏、历史一样始终保存在本机；不参与云同步
export const customRecipes = useStorage<CustomRecipe[]>(`${namespace}:custom-recipes`, [])

/** Read current storage at commit time so another tab's additions are preserved. */
export function updateCustomRecipes(update: (current: CustomRecipe[]) => CustomRecipe[]) {
  const recipes = updatePrivateRecipeStorage(localStorage, `${namespace}:custom-recipes`, update)
  customRecipes.value = recipes
}

export function getCustomRecipe(customId: string) {
  return customRecipes.value.find(recipe => recipe.customId === customId)
}

export function saveCustomRecipe(recipe: CustomRecipe) {
  updateCustomRecipes((current) => {
    const exists = current.some(item => item.customId === recipe.customId)
    return exists ? current.map(item => item.customId === recipe.customId ? recipe : item) : [recipe, ...current]
  })
}

export function removeCustomRecipe(customId: string) {
  updateCustomRecipes(current => current.filter(recipe => recipe.customId !== customId))
}
