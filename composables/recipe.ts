import type { DbRecipeItem } from 'utils/db'

/**
 * 随机几道菜
 * @param total
 * @returns
 */
export function useRandomRecipe(total: Ref<number>) {
  const randomRecipes = ref<(DbRecipeItem | undefined)[]>([])
  async function random() {
    const length = await db.recipes.count()
    const randomArr = generateRandomArray(length, total.value)
    const result = await db.recipes.bulkGet(randomArr)
    if (result)
      randomRecipes.value = result.filter(item => !!item)
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
