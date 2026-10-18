import type { DbRecipeItem } from '~/utils/db'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'

/**
 * 随机几道菜
 * @param total
 */
export function useRandomRecipe(total: Ref<number>) {
  const randomRecipes = useStorage<(DbRecipeItem | undefined)[]>(`${namespace}:random:recipes`, [])
  async function random() {
    // id 可能不连续（菜谱删除后留空），按实际存在的主键抽样
    const ids = await db.recipes.toCollection().primaryKeys()
    const randomArr = generateRandomArray(ids.length, Math.min(total.value, ids.length)).map(i => ids[i]!)
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
