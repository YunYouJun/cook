import type { YunleHostEnv, YunleJSAPI } from '../app/utils/yunleHost'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  applyYunleHostLayout,
  capsuleInset,
  getYunleHost,
  hostLayoutVars,
  resetYunleHostCache,
  resolveDisplayMode,
  waitForYunleHost,
  yunleHaptic,
} from '../app/utils/yunleHost'

function env(overrides: Partial<YunleHostEnv> = {}): YunleHostEnv {
  return {
    platform: 'ios',
    appId: 'cook',
    displayMode: 'immersive',
    trusted: true,
    capsuleVisible: true,
    titleBarVisible: false,
    safeAreaInsets: { top: 47, bottom: 34, left: 0, right: 0 },
    capsuleRect: { x: 283, y: 51, width: 84, height: 32 },
    ...overrides,
  }
}

function host(overrides: Partial<YunleJSAPI> = {}): YunleJSAPI {
  return {
    version: 3,
    inYunleApp: true,
    canIUse: vi.fn(async () => true),
    getEnv: vi.fn(async () => env()),
    haptic: vi.fn(async () => {}),
    ...overrides,
  }
}

afterEach(() => {
  delete window.ylf
  resetYunleHostCache()
  document.documentElement.className = ''
  document.documentElement.removeAttribute('style')
})

describe('yunle host detection', () => {
  it('ignores plain browsers and look-alike objects', () => {
    expect(getYunleHost({})).toBeUndefined()
    expect(getYunleHost({ ylf: { inYunleApp: false } as unknown as YunleJSAPI })).toBeUndefined()
    expect(getYunleHost({ ylf: { inYunleApp: true } as unknown as YunleJSAPI })).toBeUndefined()
  })

  it('detects the injected JSAPI', () => {
    const api = host()
    expect(getYunleHost({ ylf: api })).toBe(api)
  })

  it('waits for late injection after load', async () => {
    vi.useFakeTimers()
    const win = {
      ylf: undefined as YunleJSAPI | undefined,
      document: { readyState: 'loading' },
      addEventListener: vi.fn((_: string, listener: () => void) => {
        win.ylf = host()
        listener()
      }),
      setTimeout: (fn: () => void, ms: number) => setTimeout(fn, ms),
    }
    const pending = waitForYunleHost(win as never)
    await vi.runAllTimersAsync()
    await expect(pending).resolves.toBe(win.ylf)
    vi.useRealTimers()
  })
})

describe('yunle host layout', () => {
  it('treats a missing display mode as immersive', () => {
    expect(resolveDisplayMode({})).toBe('immersive')
    expect(resolveDisplayMode({ displayMode: 'titlebar' })).toBe('titlebar')
  })

  it('reserves space for a visible capsule only', () => {
    expect(capsuleInset(env(), 375)).toBe(100)
    expect(capsuleInset(env({ capsuleVisible: false }), 375)).toBe(0)
    // 标题栏显示时胶囊在视口上方
    expect(capsuleInset(env({ capsuleRect: { x: 283, y: -40, width: 84, height: 32 } }), 375)).toBe(0)
  })

  it('maps host insets to Ionic safe-area variables', () => {
    expect(hostLayoutVars(env(), 375)).toEqual({
      '--ion-safe-area-top': '47px',
      '--ion-safe-area-bottom': '34px',
      '--ion-safe-area-left': '0px',
      '--ion-safe-area-right': '0px',
      '--cook-host-capsule-inset': '100px',
    })
  })

  it('marks the root with the display mode', () => {
    const root = document.documentElement
    applyYunleHostLayout(root, env({ displayMode: 'titlebar', safeAreaInsets: { top: 0, bottom: 34, left: 0, right: 0 } }), 375)
    expect(root.classList.contains('in-yunle-app')).toBe(true)
    expect(root.classList.contains('ylf-mode-titlebar')).toBe(true)
    expect(root.style.getPropertyValue('--ion-safe-area-top')).toBe('0px')

    applyYunleHostLayout(root, env(), 375)
    expect(root.classList.contains('ylf-mode-titlebar')).toBe(false)
    expect(root.classList.contains('ylf-mode-immersive')).toBe(true)
  })
})

describe('yunle host haptic', () => {
  it('does nothing outside the host', async () => {
    await expect(yunleHaptic()).resolves.toBeUndefined()
  })

  it('calls haptic once permission is confirmed', async () => {
    const api = host()
    window.ylf = api
    await yunleHaptic('light')
    await yunleHaptic('success')
    expect(api.canIUse).toHaveBeenCalledTimes(1)
    expect(api.haptic).toHaveBeenNthCalledWith(1, 'light')
    expect(api.haptic).toHaveBeenNthCalledWith(2, 'success')
  })

  it('skips untrusted pages without throwing', async () => {
    const api = host({ canIUse: vi.fn(async () => false) })
    window.ylf = api
    await yunleHaptic()
    expect(api.haptic).not.toHaveBeenCalled()
  })
})
