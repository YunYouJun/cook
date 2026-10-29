import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

describe('auth initialization plugin', () => {
  const initialize = vi.fn(async () => {})
  const ready = vi.fn()
  const homeReady = vi.fn()

  beforeEach(() => {
    vi.useFakeTimers()
    vi.resetModules()
    initialize.mockClear()
    ready.mockClear()
    homeReady.mockClear()
    window.history.replaceState({}, '', '/home')
    vi.stubGlobal('defineNuxtPlugin', (plugin: { setup: () => void }) => plugin)
    vi.stubGlobal('useYunlefunAuth', () => ({ initialize }))
    vi.stubGlobal('onNuxtReady', ready)
    vi.stubGlobal('useNuxtApp', () => ({ hooks: { hookOnce: homeReady } }))
  })

  afterEach(() => {
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.unstubAllGlobals()
    window.history.replaceState({}, '', '/')
  })

  it.each(['/', '/home', '/home/', '/tabs', '/tabs/home'])('waits for the Ionic home view on %s', async (path) => {
    window.history.replaceState({}, '', path)
    const { default: plugin } = await import('../apps/web/app/plugins/yunlefun-auth.client')
    plugin.setup()

    expect(initialize).not.toHaveBeenCalled()
    expect(homeReady).toHaveBeenCalledExactlyOnceWith('cook:home-ready', expect.any(Function))
    expect(ready).not.toHaveBeenCalled()
  })

  it.each(['/my', '/library', '/catalog'])('uses Nuxt readiness for other initial pages: %s', async (path) => {
    window.history.replaceState({}, '', path)
    const { default: plugin } = await import('../apps/web/app/plugins/yunlefun-auth.client')
    plugin.setup()

    expect(initialize).not.toHaveBeenCalled()
    expect(homeReady).not.toHaveBeenCalled()
    expect(ready).toHaveBeenCalledOnce()
  })

  it.each([
    '/auth/callback',
    '/auth/callback?code=callback-code',
    '/auth/callback?platform=native#sso-result=callback-result',
    '/auth/callback/?platform=native',
  ])('initializes %s immediately without waiting for page readiness', async (path) => {
    window.history.replaceState({}, '', path)
    const { default: plugin } = await import('../apps/web/app/plugins/yunlefun-auth.client')
    plugin.setup()

    expect(initialize).toHaveBeenCalledOnce()
    expect(ready).not.toHaveBeenCalled()
    expect(homeReady).not.toHaveBeenCalled()
  })
})
