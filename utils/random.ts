import type { RecipeItem, Recipes } from '../types'

/**
 * 生成随机菜谱，默认一道
 * @param recipes
 * @returns
 */
export function generateRandomRecipe(recipes: Recipes, total = 1) {
  const randomRecipes: RecipeItem[] = []
  for (let i = 0; i < total; i++) {
    const randomIndex = Math.floor(Math.random() * recipes.length)
    if (randomRecipes.includes(recipes[randomIndex])) {
      i--
      continue
    }
    randomRecipes.push(recipes[randomIndex])
  }
  return randomRecipes
}
