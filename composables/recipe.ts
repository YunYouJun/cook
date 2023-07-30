import recipeData from '~/data/recipe.json'
import type { RecipeItem, Recipes } from '~/types'

/**
 * 随机几道菜
 * @param total
 * @returns
 */
export function useRandomRecipe(total: Ref<number>) {
  const randomRecipes = ref<RecipeItem[]>([])
  function random() {
    randomRecipes.value = generateRandomRecipe(recipeData as Recipes, total.value)
  }

  watch(total, () => {
    random()
  })

  onMounted(() => {
    random()
  })

  return {
    random,

    randomRecipes,
  }
}
