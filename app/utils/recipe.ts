import type { RecipeItem } from '~/types'

/**
 * 菜谱的做法链接：优先使用收录的链接，否则拼出 B 站视频地址
 */
export function getRecipeLink(recipe: Pick<RecipeItem, 'link' | 'bv'>) {
  return recipe.link || `https://www.bilibili.com/video/${recipe.bv}`
}

export function openRecipe(recipe: Pick<RecipeItem, 'link' | 'bv'>) {
  window.open(getRecipeLink(recipe), '_blank')
}
