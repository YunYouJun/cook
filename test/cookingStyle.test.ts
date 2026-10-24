import { renderToString } from '@vue/server-renderer'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createSSRApp, defineComponent, h, nextTick } from 'vue'
import { COOKING_STYLE_KEY, useCookingStyle } from '../apps/web/app/composables/useCookingStyle'

vi.unmock('vue')
let state: ReturnType<typeof useCookingStyle>
const Host = defineComponent({
  setup() {
    state = useCookingStyle()
    return () => h('div', state.style.value)
  },
})

beforeEach(() => localStorage.clear())
afterEach(() => vi.restoreAllMocks())

describe('cooking style preference', () => {
  it('defaults to classic, persists only explicit pantry choices, and restores both on remount', async () => {
    const wrapper = mount(Host)
    expect(wrapper.text()).toBe('classic')
    expect(state.pantry.value).toEqual([])
    state.style.value = 'workbench'
    state.pantry.value = ['盐']
    await nextTick()
    wrapper.unmount()
    const restored = mount(Host)
    await nextTick()
    expect(restored.text()).toBe('workbench')
    expect(state.pantry.value).toEqual(['盐'])
    state.style.value = 'classic'
    await nextTick()
    expect(JSON.parse(localStorage.getItem(COOKING_STYLE_KEY)!)).toEqual({ style: 'classic', pantry: ['盐'] })
    restored.unmount()
  })

  it('validates saved values and drops duplicate or unknown pantry entries', async () => {
    localStorage.setItem(COOKING_STYLE_KEY, JSON.stringify({ style: 'not-a-mode', pantry: ['盐', '盐', null, '不存在'] }))
    const wrapper = mount(Host)
    await nextTick()
    expect(state.style.value).toBe('classic')
    expect(state.pantry.value).toEqual(['盐'])
    wrapper.unmount()
  })

  it('recovers from malformed stored JSON on the next choice', async () => {
    localStorage.setItem(COOKING_STYLE_KEY, '{broken')
    const wrapper = mount(Host)
    expect(state.style.value).toBe('classic')
    state.style.value = 'workbench'
    await nextTick()
    expect(state.canPersist.value).toBe(true)
    expect(JSON.parse(localStorage.getItem(COOKING_STYLE_KEY)!)).toEqual({ style: 'workbench', pantry: [] })
    wrapper.unmount()
  })

  it('remains usable when browser storage is denied', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    const wrapper = mount(Host)
    state.style.value = 'workbench'
    await nextTick()
    expect(wrapper.text()).toBe('workbench')
    expect(state.canPersist.value).toBe(false)
    wrapper.unmount()
  })

  it('never accesses storage while rendering on the server', async () => {
    const read = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('SSR must not read browser storage')
    })
    const write = vi.spyOn(Storage.prototype, 'setItem')
    expect(await renderToString(createSSRApp(Host))).toBe('<div>classic</div>')
    expect(read).not.toHaveBeenCalled()
    expect(write).not.toHaveBeenCalled()
  })
})
