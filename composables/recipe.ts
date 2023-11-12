import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'
import type { DbRecipeItem } from '~/utils/db'

/**
 * 随机几道菜
 * @param total
 */
export function useRandomRecipe(total: Ref<number>) {
  const randomRecipes = useStorage<(DbRecipeItem | undefined)[]>(`${namespace}:random:recipes`, [])
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
    // 如果没有随机菜谱，就生成一次
    if (randomRecipes.value.length <= 0)
      random()
  })

  return {
    random,

    randomRecipes,
  }
}
