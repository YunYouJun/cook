import recipeData from '~/data/recipe.json'
import type { RecipeItem, Recipes } from '~/types'

export function useRandomRecipe() {
  const randomRecipe = ref<RecipeItem>()
  function random() {
    randomRecipe.value = generateRandomRecipe(recipeData as Recipes)
  }

  onMounted(() => {
    random()
  })

  return {
    random,

    randomRecipe,
  }
}
