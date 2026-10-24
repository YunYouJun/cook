import type { MaybeRefOrGetter } from 'vue'
import type { StuffItem } from '~/types'
import { computed, toValue } from 'vue'
import { usePersonalCatalog } from './usePersonalCatalog'

export function useWorkbenchCatalog(selectedTool: MaybeRefOrGetter<string>) {
  const { sections, ingredients, food } = usePersonalCatalog()
  const tools = computed<StuffItem[]>(() => {
    const visible = food.value.tools
    const selected = toValue(selectedTool)
    return selected && !visible.some(item => item.name === selected)
      ? [...visible, { name: selected, emoji: '🍳' }]
      : visible
  })

  function acceptsIngredient(name: string) {
    return ingredients.value.some(item => item.name === name)
  }

  return { sections, ingredients, tools, acceptsIngredient }
}
