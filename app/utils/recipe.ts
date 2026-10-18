import type { RecipeItem } from '~/types'

/**
 * 菜谱的做法链接：优先使用收录的链接，否则拼出 B 站视频地址；两者都没有时返回空字符串
 */
export function getRecipeLink(recipe: Pick<RecipeItem, 'link' | 'bv'>) {
  if (recipe.link)
    return recipe.link
  return recipe.bv ? `https://www.bilibili.com/video/${recipe.bv}` : ''
}

export function openRecipe(recipe: Pick<RecipeItem, 'link' | 'bv'>) {
  const link = getRecipeLink(recipe)
  if (link)
    window.open(link, '_blank')
}
