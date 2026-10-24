import type { PersonalCatalog } from '@yunyoujun/cook/personal-catalog'
import type { StuffItem } from '~/types'
import { useEventListener } from '@vueuse/core'
import { catalogItems, emptyPersonalCatalog } from '@yunyoujun/cook/personal-catalog'
import { computed, onMounted, shallowRef } from 'vue'
import { namespace } from '~/constants'
import * as base from '~/data/food'
import { readPersonalCatalog, updatePersonalCatalog } from '~/utils/personalCatalogStorage'

const key = `${namespace}:personal-catalog`
const value = shallowRef(emptyPersonalCatalog())
const error = shallowRef('')
export const catalogTitles = { vegetable: '蔬菜', meat: '肉和蛋', staple: '主食', tools: '厨具' }
const emoji = { vegetable: '🥬', meat: '🥩', staple: '🍚', tools: '🍳' }

export function usePersonalCatalog() {
  function refresh() {
    try {
      value.value = readPersonalCatalog(localStorage, key)
      error.value = ''
    }
    catch {
      error.value = '本机食材和厨具配置读取失败，原始数据已保留；请重试或先备份浏览器数据。'
    }
  }
  onMounted(refresh)
  useEventListener('storage', (event: StorageEvent) => {
    if (event.key === key || event.key === null)
      refresh()
  })
  function update(change: (current: PersonalCatalog) => PersonalCatalog) {
    value.value = updatePersonalCatalog(localStorage, key, change)
    error.value = ''
  }
  const food = computed(() => Object.fromEntries(Object.entries(base).map(([category, items]) => {
    const group = category as keyof typeof base
    return [group, catalogItems(items, value.value, group, name => ({ name, emoji: emoji[group] }))]
  })) as Record<keyof typeof base, StuffItem[]>)
  const sections = computed(() => (['vegetable', 'meat', 'staple'] as const).map(category => ({ category, title: catalogTitles[category], items: food.value[category] })))
  const ingredients = computed(() => sections.value.flatMap(section => section.items))
  return { value, error, refresh, update, food, sections, ingredients }
}
