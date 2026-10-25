import type { VueWrapper } from '@vue/test-utils'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, shallowRef } from 'vue'
import * as landing from '../docs/.vitepress/theme/landing'
import { usePhoneDemo } from '../docs/.vitepress/theme/use-phone-demo'

vi.unmock('vue')
vi.mock('../docs/.vitepress/theme/recipes.data', async () => {
  const { readFileSync } = await import('node:fs')
  const { parseRecipeCsv } = await import('../packages/cook/src/utils/csv')
  const { compactRecipe } = await import('../docs/.vitepress/theme/landing-data')
  const recipes = parseRecipeCsv(readFileSync('packages/data/recipe.csv', 'utf8'))
  return { data: { total: recipes.length, recipes: recipes.map(compactRecipe) } }
})

let wrapper: VueWrapper | undefined
let motion: MediaQueryList

function mountDemo() {
  const root = document.createElement('div')
  const screen = document.createElement('div')
  screen.innerHTML = '<span data-demo="search"></span><span data-demo="chip-土豆"></span><span data-demo="see"></span><span data-demo="roll"></span>'
  root.append(screen)
  let demo!: ReturnType<typeof usePhoneDemo>
  wrapper = mount(defineComponent({
    setup() {
      demo = usePhoneDemo(shallowRef(root), shallowRef(screen))
      return () => h('div')
    },
  }))
  return demo
}

beforeEach(() => {
  vi.useFakeTimers()
  motion = Object.assign(new EventTarget(), { matches: false, media: '(prefers-reduced-motion: reduce)' }) as MediaQueryList
  vi.stubGlobal('matchMedia', vi.fn(() => motion))
  vi.stubGlobal('IntersectionObserver', undefined)
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('homepage phone playback', () => {
  it('waits for the visitor and finishes only the requested chapter', async () => {
    const demo = mountDemo()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(demo.playback.value).toBe('idle')
    expect(demo.selected.value).toEqual([])

    demo.choose(0)
    await nextTick()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(demo.active.value).toBe(0)
    expect(demo.screen.value).toBe('results')
    expect(demo.playback.value).toBe('finished')
    expect(demo.resultStuff.value).toEqual(['土豆', '番茄', '鸡蛋'])
  })

  it('applies the sentence together on submission without ingredient flights', async () => {
    const flight = vi.spyOn(landing, 'flyEmoji')
    const demo = mountDemo()
    demo.choose(1)
    await nextTick()
    await vi.advanceTimersByTimeAsync(1500)
    expect(demo.searching.value).toBe(true)
    expect(demo.query.value.length).toBeGreaterThan(0)
    expect(demo.selected.value).toEqual([])

    await vi.advanceTimersByTimeAsync(3000)
    expect(demo.selected.value).toEqual(['白菜', '鸡蛋'])
    expect(demo.basket.value).toEqual(['白菜', '鸡蛋'])
    expect(demo.noSpicy.value).toBe(true)
    expect(demo.notice.value).toContain('白菜、鸡蛋 · 不辣')
    expect(demo.displayCount.value).toBe(landing.recommend({ stuff: ['白菜', '鸡蛋'], noSpicy: true }).length)
    expect(flight).not.toHaveBeenCalled()
  })

  it('settles the random card when paused and cancels subsequent updates', async () => {
    const demo = mountDemo()
    demo.choose(3)
    await nextTick()
    await vi.advanceTimersByTimeAsync(1000)
    expect(demo.shuffleEmoji.value).not.toBe('')
    demo.togglePause()
    const recipe = demo.card.value
    expect(demo.playback.value).toBe('paused')
    expect(demo.shuffleEmoji.value).toBe('')
    expect(demo.shaking.value).toBe(false)
    expect(demo.touch.value.n).toBe(0)

    await vi.advanceTimersByTimeAsync(10_000)
    expect(demo.card.value).toBe(recipe)
    expect(demo.playback.value).toBe('paused')
  })

  it('ignores a canceled flight after switching chapters', async () => {
    let finishFlight!: () => void
    const flight = vi.spyOn(landing, 'flyEmoji').mockImplementation(() => new Promise<void>((resolve) => {
      finishFlight = resolve
    }))
    const demo = mountDemo()
    demo.choose(0)
    await nextTick()
    await vi.advanceTimersByTimeAsync(1000)
    expect(flight).toHaveBeenCalledOnce()
    demo.choose(1)
    await nextTick()
    expect(flight.mock.calls[0]![4]!.signal!.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(4500)
    finishFlight()
    await nextTick()
    expect(demo.selected.value).toEqual(['白菜', '鸡蛋'])
    expect(demo.basket.value).toEqual(['白菜', '鸡蛋'])
  })

  it('shows static results with reduced motion and stops when the preference changes', async () => {
    Object.assign(motion, { matches: true })
    const demo = mountDemo()
    demo.choose(2)
    await nextTick()
    await vi.advanceTimersByTimeAsync(0)
    expect(demo.playback.value).toBe('finished')
    expect(demo.banner.value).toBe(true)
    expect(vi.getTimerCount()).toBe(0)

    Object.assign(motion, { matches: false })
    motion.dispatchEvent(new Event('change'))
    demo.choose(1)
    await nextTick()
    await vi.advanceTimersByTimeAsync(1500)
    expect(demo.running.value).toBe(true)
    Object.assign(motion, { matches: true })
    motion.dispatchEvent(new Event('change'))
    const text = demo.query.value
    await vi.advanceTimersByTimeAsync(10_000)
    expect(demo.running.value).toBe(false)
    expect(demo.query.value).toBe(text)
    expect(demo.selected.value).toEqual([])
  })
})
