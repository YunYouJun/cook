import type { RecipeItem } from '~/types'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'

/**
 * 随机几道菜：候选跟随首页选的食材、厨具和忌口（见 recipe store 的 randomPool）
 * @param total
 */
export function useRandomRecipe(total: Ref<number>) {
  const rStore = useRecipeStore()
  const randomRecipes = useStorage<(RecipeItem | undefined)[]>(`${namespace}:random:recipes`, [])

  async function random() {
    await rStore.loadRecipes()
    const pool = rStore.randomPool
    const indexes = generateRandomArray(pool.length, Math.min(total.value, pool.length))
    randomRecipes.value = indexes.map(i => pool[i]).filter(item => !!item)
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
