import type { CookFunnelState } from '../apps/web/app/composables/useCookFunnel'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { computed, effectScope, nextTick, reactive } from 'vue'
import { useRecipeStore } from '../apps/web/app/composables/store/recipe'
import { useCookFunnel } from '../apps/web/app/composables/useCookFunnel'
import { setCookEventTracker } from '../apps/web/app/utils/analytics'

const scopes: ReturnType<typeof effectScope>[] = []
afterEach(() => {
  scopes.splice(0).forEach(scope => scope.stop())
  setCookEventTracker(() => {})
})

function setup(initial: Partial<CookFunnelState> = {}) {
  const send = vi.fn()
  setCookEventTracker(send)
  const state = reactive<CookFunnelState>({ active: false, hasIngredients: false, results: 'hidden', ...initial })
  const scope = effectScope()
  scopes.push(scope)
  scope.run(() => useCookFunnel(computed(() => ({ ...state }))))
  return { state, send }
}

describe('cook funnel', () => {
  it('ignores cached hidden pages, and counts entry with restored selections in order', async () => {
    const { state, send } = setup({ hasIngredients: true, results: 'ready' })
    expect(send).not.toHaveBeenCalled()
    state.active = true
    await nextTick()
    expect(send.mock.calls.flat()).toEqual(['view_cook', 'select_ingredients', 'view_results'])
    state.active = false
    state.results = 'empty'
    await nextTick()
    expect(send).toHaveBeenCalledTimes(3)
  })

  it('counts actual visible results, never loading or collapsed placeholders', async () => {
    const { state, send } = setup({ active: true, hasIngredients: true })
    state.results = 'loading'
    await nextTick()
    expect(send.mock.calls.flat()).toEqual(['view_cook', 'select_ingredients'])
    state.results = 'ready'
    await nextTick()
    state.results = 'ready'
    await nextTick()
    expect(send.mock.calls.flat()).toEqual(['view_cook', 'select_ingredients', 'view_results'])
    state.results = 'hidden'
    await nextTick()
    state.results = 'empty'
    await nextTick()
    expect(send.mock.calls.flat()).toEqual(['view_cook', 'select_ingredients', 'view_results', 'results_empty'])
  })

  it('covers chips, input, examples and suggestions through shared selection state without duplicate additions', async () => {
    setActivePinia(createPinia())
    const store = useRecipeStore()
    const send = vi.fn()
    setCookEventTracker(send)
    const scope = effectScope()
    scopes.push(scope)
    scope.run(() => useCookFunnel(computed(() => ({
      active: true,
      hasIngredients: store.selectedStuff.length > 0,
      results: 'hidden',
    }))))
    store.toggleStuff('鸡蛋')
    await nextTick()
    store.addStuff('番茄')
    store.addStuff('西红柿')
    await nextTick()
    expect(send.mock.calls.flat()).toEqual(['view_cook', 'select_ingredients'])
    store.reset()
    await nextTick()
    store.addStuff('番茄、鸡蛋')
    await nextTick()
    expect(send.mock.calls.flat()).toEqual(['view_cook', 'select_ingredients', 'select_ingredients'])
    store.reset()
    await nextTick()
    store.addStuff(' ')
    await nextTick()
    expect(send).toHaveBeenCalledTimes(3)
  })

  it('supports tool-only results without inventing ingredient selection', async () => {
    const { state, send } = setup({ active: true })
    state.results = 'ready'
    await nextTick()
    expect(send.mock.calls.flat()).toEqual(['view_cook', 'view_results'])
  })
})
