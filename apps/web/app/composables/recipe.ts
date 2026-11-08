import type { Ref } from 'vue'
import type { RecipeItem } from '~/types'
import { useStorage } from '@vueuse/core'
import { computed, onMounted, shallowRef, watch } from 'vue'
import { useRecipeStore } from '~/composables/store/recipe'
import { namespace } from '~/constants'
import { generateRandomArray } from '~/utils/random'

/**
 * 随机几道菜：候选跟随首页选的食材、厨具和忌口（见 recipe store 的 randomPool）
 * @param total
 */
export function useRandomRecipe(total: Ref<number>) {
  const rStore = useRecipeStore()
  const savedRecipes = useStorage<(RecipeItem | undefined)[]>(`${namespace}:random:recipes`, [])
  const reviewedRecipes = shallowRef<(RecipeItem | undefined)[]>([])
  const randomRecipes = computed({
    get: () => rStore.hasReviewedSelection ? reviewedRecipes.value : savedRecipes.value,
    set: (recipes) => {
      if (rStore.hasReviewedSelection)
        reviewedRecipes.value = recipes
      else
        savedRecipes.value = recipes
    },
  })

  async function random() {
    if (rStore.isSearching || rStore.loadError)
      await rStore.loadRecipes()
    const pool = rStore.randomPool
    const indexes = generateRandomArray(pool.length, Math.min(total.value, pool.length))
    randomRecipes.value = indexes.map(i => pool[i]).filter(item => !!item)
  }

  watch(total, () => {
    random()
  })
  watch(() => rStore.randomPool, () => {
    // Clear synchronously so a now-excluded recipe cannot remain visible while loading.
    randomRecipes.value = []
    void random()
  }, { flush: 'sync' })

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
