import type { Recipes } from '../types'

/**
 * 生成随机菜谱
 * @param recipes
 * @returns
 */
export function generateRandomRecipe(recipes: Recipes) {
  return recipes[Math.floor(Math.random() * recipes.length)]
}
