import type { VueWrapper } from '@vue/test-utils'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { usePersonalCatalog } from '../apps/web/app/composables/usePersonalCatalog'
import { useWorkbenchCatalog } from '../apps/web/app/composables/useWorkbenchCatalog'
import { emptyPersonalCatalog, parsePersonalCatalog } from '../packages/cook/src/utils/personal-catalog'
import fixture from '../packages/data/fixtures/personal-catalog.json'

vi.unmock('vue')
const wrappers: VueWrapper[] = []

function mountCatalog(initialTool = '') {
  const selectedTool = ref(initialTool)
  let catalog: ReturnType<typeof usePersonalCatalog>
  let workbench: ReturnType<typeof useWorkbenchCatalog>
  const wrapper = mount(defineComponent({
    setup() {
      catalog = usePersonalCatalog()
      workbench = useWorkbenchCatalog(() => selectedTool.value)
      return () => h('div')
    },
  }))
  wrappers.push(wrapper)
  return { catalog: catalog!, workbench: workbench!, selectedTool }
}

beforeEach(() => localStorage.clear())
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

describe('workbench shares the personal catalog', () => {
  it('uses custom and hidden entries for categories, search ingredients and accepted drops', () => {
    const { catalog, workbench } = mountCatalog()
    catalog.update(() => parsePersonalCatalog(JSON.stringify(fixture)))

    expect(workbench.sections.value.find(section => section.category === 'vegetable')?.items.map(item => item.name)).toContain('鹰嘴豆')
    expect(workbench.sections.value.find(section => section.category === 'meat')?.items.map(item => item.name)).toContain('鹌鹑')
    expect(workbench.sections.value.find(section => section.category === 'staple')?.items.map(item => item.name)).toContain('藜麦')
    expect(workbench.ingredients.value.map(item => item.name)).not.toContain('番茄')
    expect(workbench.tools.value.map(item => item.name)).toContain('塔吉锅')
    expect(workbench.acceptsIngredient('鹰嘴豆')).toBe(true)
    expect(workbench.acceptsIngredient('番茄')).toBe(false)
    expect(workbench.acceptsIngredient('外部拖入的未知食材')).toBe(false)
  })

  it('updates an already mounted workbench when another catalog consumer adds, hides or restores entries', () => {
    const { catalog, workbench } = mountCatalog()
    catalog.update(() => ({
      ...emptyPersonalCatalog(),
      items: [{ category: 'vegetable', name: '合成食材' }],
      hidden: [{ category: 'vegetable', name: '番茄' }],
    }))
    expect(workbench.acceptsIngredient('合成食材')).toBe(true)
    expect(workbench.acceptsIngredient('番茄')).toBe(false)
    catalog.update(() => emptyPersonalCatalog())
    expect(workbench.acceptsIngredient('合成食材')).toBe(false)
    expect(workbench.acceptsIngredient('番茄')).toBe(true)
  })

  it('keeps a hidden selected tool available until it is deselected, and restores it without duplicates', () => {
    const { catalog, workbench, selectedTool } = mountCatalog('一口大锅')
    catalog.update(() => ({ ...emptyPersonalCatalog(), hidden: [{ category: 'tools', name: '一口大锅' }] }))
    expect(workbench.tools.value.map(item => item.name)).toContain('一口大锅')
    expect(selectedTool.value).toBe('一口大锅')
    selectedTool.value = ''
    expect(workbench.tools.value.map(item => item.name)).not.toContain('一口大锅')
    selectedTool.value = '一口大锅'
    catalog.update(() => emptyPersonalCatalog())
    expect(workbench.tools.value.filter(item => item.name === '一口大锅')).toHaveLength(1)
  })

  it('keeps a deleted custom tool available for cancellation without adding it back to the catalog', () => {
    const { catalog, workbench, selectedTool } = mountCatalog('塔吉锅')
    catalog.update(() => parsePersonalCatalog(JSON.stringify(fixture)))
    catalog.update(current => ({ ...current, items: current.items.filter(item => item.category !== 'tools') }))
    expect(workbench.tools.value.map(item => item.name)).toContain('塔吉锅')
    expect(catalog.food.value.tools.map(item => item.name)).not.toContain('塔吉锅')
    selectedTool.value = ''
    expect(workbench.tools.value.map(item => item.name)).not.toContain('塔吉锅')
  })
})
