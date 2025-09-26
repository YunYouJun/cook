import { ref, computed, onMounted } from 'vue'
import type { IncompatibleRule } from '~/types'
import { computed, onMounted, readonly, ref } from 'vue'
import incompatibleFoodsData from '~/data/incompatible-foods.json'

/**
 * 食物相克检测 composable
 */
export function useIncompatibleFoods() {
  // 用于存储从 JSON 加载的相克规则
  const incompatibleRules = ref<IncompatibleRule[]>([])
  // 用于存储并显示给用户的警告信息
  const warningMessage = ref<string>('')
  // 加载状态
  const isLoading = ref(true)

  /**
   * 在组件挂载后，加载食物相克数据
   */
  onMounted(() => {
    try {
      // 直接使用导入的数据
      incompatibleRules.value = incompatibleFoodsData as IncompatibleRule[]
    } catch (error) {
      console.error('Failed to load incompatible foods data:', error)
    } finally {
      isLoading.value = false
    }
  })

  /**
   * 核心检测函数：检查当前选择的食材是否存在相克组合
   * @param ingredients - 当前已选的食材列表
   */
  const checkIncompatibility = (ingredients: string[]) => {
    // 重置警告信息
    warningMessage.value = ''

    // 如果食材少于2个或规则还没加载完成，无需检测
    if (ingredients.length < 2 || isLoading.value) {
      return
    }

    const foundRules: IncompatibleRule[] = []

    // 使用嵌套循环检查所有食材对 (O(n^2))
    // 对于少量食材（<20），性能完全足够
    for (let i = 0; i < ingredients.length; i++) {
      for (let j = i + 1; j < ingredients.length; j++) {
        const food1 = ingredients[i]
        const food2 = ingredients[j]

        // 在规则库中查找匹配的组合
        const foundRule = incompatibleRules.value.find(rule =>
          (rule.foodA === food1 && rule.foodB === food2) ||
          (rule.foodA === food2 && rule.foodB === food1)
        )

        // 收集所有找到的相克规则
        if (foundRule) {
          foundRules.push(foundRule)
        }
      }
    }

    // 如果找到相克组合，生成警告信息
    if (foundRules.length > 0) {
      if (foundRules.length === 1) {
        const rule = foundRules[0]!
        warningMessage.value =
          `🚨 危险组合！\n`
          + `【${rule.foodA}】+ 【${rule.foodB}】= 有毒？！\n`
          + `${rule.reason}\n`
          + `换个搭配会更安全哦～`
      } else {
        const warnings = foundRules.map(rule =>
          `【${rule.foodA}】+ 【${rule.foodB}】（${rule.reason}）\n`
        ).join('')
        warningMessage.value =
          `🚨 发现 ${foundRules.length} 个危险组合！\n`
          + `${warnings}`
          + `建议调整搭配哦～`
      }
    }
  }

  // 计算属性：是否有警告信息
  const hasWarning = computed(() => Boolean(warningMessage.value))

  return {
    incompatibleRules: readonly(incompatibleRules),
    warningMessage: readonly(warningMessage),
    hasWarning,
    isLoading: readonly(isLoading),
    checkIncompatibility,
  }
}
