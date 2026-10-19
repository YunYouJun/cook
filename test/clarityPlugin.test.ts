import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function setup(projectId = 'ks1ebw03i5', path = '/') {
  vi.resetModules()
  window.history.replaceState({}, '', path)
  const send = vi.fn()
  const load = vi.fn().mockResolvedValue({})
  const script = vi.fn(() => ({ proxy: { clarity: send }, load }))
  const ready = vi.fn()
  const beforeEach = vi.fn()
  vi.stubGlobal('defineNuxtPlugin', (setup: () => void) => setup)
  vi.stubGlobal('useRuntimeConfig', () => ({ public: { clarityProjectId: projectId, clarityDomains: window.location.hostname } }))
  vi.stubGlobal('useScriptClarity', script)
  vi.stubGlobal('onNuxtReady', ready)
  vi.stubGlobal('useRouter', () => ({ beforeEach }))
  const { default: plugin } = await import('../apps/web/app/plugins/clarity.client')
  ;(plugin as unknown as () => void)()
  return { send, load, script, ready, beforeEach }
}

describe('clarity client integration', () => {
  it('loads once after Nuxt is ready and forwards legacy and funnel action names', async () => {
    const { send, load, script, ready } = await setup()
    expect(script).toHaveBeenCalledExactlyOnceWith({
      id: 'ks1ebw03i5',
      scriptOptions: { bundle: false, proxy: false, trigger: 'manual' },
      scriptInput: { src: 'https://www.clarity.ms/tag/ks1ebw03i5', referrerpolicy: 'no-referrer' },
    })
    expect(load).not.toHaveBeenCalled()
    ready.mock.calls[0]![0]()
    expect(load).toHaveBeenCalledTimes(1)
    const { trackCookEvent } = await import('../apps/web/app/utils/analytics')
    trackCookEvent('click_stuff')
    trackCookEvent('click_tool')
    trackCookEvent('click_dish')
    trackCookEvent('view_cook')
    trackCookEvent('select_ingredients')
    trackCookEvent('view_results')
    trackCookEvent('results_empty')
    trackCookEvent('view_recipe_detail')
    trackCookEvent('click_recipe_video')
    expect(send.mock.calls).toEqual([
      ['event', 'click_stuff'],
      ['event', 'click_tool'],
      ['event', 'click_dish'],
      ['event', 'view_cook'],
      ['event', 'select_ingredients'],
      ['event', 'view_results'],
      ['event', 'results_empty'],
      ['event', 'view_recipe_detail'],
      ['event', 'click_recipe_video'],
    ])
  })

  it('skips disabled projects and direct auth callback entries', async () => {
    expect((await setup('')).script).not.toHaveBeenCalled()
    expect((await setup('ks1ebw03i5', '/auth/callback?ticket=secret')).script).not.toHaveBeenCalled()
  })

  it('respects Do Not Track', async () => {
    vi.stubGlobal('navigator', { doNotTrack: '1' })
    expect((await setup()).script).not.toHaveBeenCalled()
  })

  it('stops before navigating to auth and prevents a deferred script load or more events', async () => {
    const stop = vi.fn()
    vi.stubGlobal('clarity', stop)
    const { beforeEach, ready, load, send } = await setup()
    beforeEach.mock.calls[0]![0]({ fullPath: '/auth/callback?ticket=secret' })
    expect(stop).toHaveBeenCalledWith('stop')
    ready.mock.calls[0]![0]()
    expect(load).not.toHaveBeenCalled()
    const { trackCookEvent } = await import('../apps/web/app/utils/analytics')
    trackCookEvent('click_dish')
    expect(send).not.toHaveBeenCalled()
  })
})
