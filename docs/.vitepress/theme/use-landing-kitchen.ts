import { computed, onBeforeUnmount, readonly, ref, shallowRef } from 'vue'
import { kitchenMotion } from './cloud-kitchen/motion'
import { prefersReducedMotion, recommend, stuffGroups } from './landing'

const featuredNames = ['土豆', '番茄', '鸡蛋', '胡萝卜', '黄瓜', '豆腐', '鸡肉', '米']
const allIngredients = stuffGroups.flatMap(group => group.items)

export function useLandingKitchen() {
  const selected = ref<string[]>(['番茄', '鸡蛋'])
  const expanded = shallowRef(false)
  const cooking = shallowRef(false)
  const served = shallowRef(false)
  let finishCooking: ReturnType<typeof setTimeout> | undefined

  const ingredients = computed(() => expanded.value
    ? allIngredients
    : allIngredients.filter(item => featuredNames.includes(item.name) || selected.value.includes(item.name))
        .sort((a, b) => {
          const rank = (name: string) => featuredNames.includes(name) ? featuredNames.indexOf(name) : featuredNames.length
          return rank(a.name) - rank(b.name)
        }))
  const matches = computed(() => selected.value.length ? recommend({ stuff: selected.value }) : [])
  const featuredRecipes = computed(() => {
    const seen = new Set<string>()
    return matches.value.filter((match) => {
      const name = match.recipe.name.replace(/^\d+\s*阶/, '').replaceAll('西红柿', '番茄')
      if (seen.has(name))
        return false
      seen.add(name)
      return true
    }).slice(0, 3)
  })

  function stopCooking() {
    clearTimeout(finishCooking)
    cooking.value = false
    served.value = false
  }

  function toggle(name: string) {
    stopCooking()
    selected.value = selected.value.includes(name)
      ? selected.value.filter(item => item !== name)
      : [...selected.value, name]
  }

  function clear() {
    stopCooking()
    selected.value = []
  }

  function cook() {
    if (!matches.value.length || cooking.value)
      return
    served.value = true
    cooking.value = !prefersReducedMotion()
    clearTimeout(finishCooking)
    finishCooking = setTimeout(() => {
      cooking.value = false
    }, kitchenMotion.cooking * 1000)
  }

  function toggleExpanded() {
    expanded.value = !expanded.value
  }

  onBeforeUnmount(() => clearTimeout(finishCooking))

  return {
    selected: readonly(selected),
    expanded: readonly(expanded),
    cooking: readonly(cooking),
    served: readonly(served),
    ingredients,
    matches,
    featuredRecipes,
    toggle,
    clear,
    cook,
    toggleExpanded,
  }
}
