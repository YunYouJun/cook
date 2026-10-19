import type { RecipeItem } from '~/types'
import { trackCookEvent } from './analytics'

/**
 * 菜谱的做法链接：优先用 B 站视频，没有视频时用收录的链接（图文做法或自己填的链接）；两者都没有时返回空字符串
 */
export function getRecipeLink(recipe: Pick<RecipeItem, 'link' | 'bv'>) {
  if (recipe.bv)
    return `https://www.bilibili.com/video/${recipe.bv}`
  return recipe.link || ''
}

/**
 * 视频之外的图文做法链接：只有视频和图文都有时才返回，作为第二个入口
 */
export function getRecipeTextLink(recipe: Pick<RecipeItem, 'link' | 'bv'>) {
  return recipe.bv && recipe.link ? recipe.link : ''
}

export function openRecipe(recipe: Pick<RecipeItem, 'link' | 'bv'>) {
  const link = getRecipeLink(recipe)
  if (!link)
    return
  trackCookEvent('click_recipe_video')
  window.open(link, '_blank')
}

/**
 * 反馈这道菜的问题：打开预填好菜名和视频的 GitHub Issue 表单
 */
export function getRecipeFeedbackLink(recipe: Pick<RecipeItem, 'name' | 'link' | 'bv'>) {
  const params = new URLSearchParams({
    template: 'recipe.yml',
    title: `[菜谱] ${recipe.name}`,
    name: recipe.name,
    video: getRecipeLink(recipe),
  })
  return `https://github.com/YunYouJun/cook/issues/new?${params}`
}
