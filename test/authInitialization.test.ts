import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isAuthCallbackUrl, scheduleAuthInitialization } from '../apps/web/app/utils/authInitialization'

describe('auth callback path recognition', () => {
  it.each([
    '/auth/callback',
    '/auth/callback/',
    '/auth/callback/?platform=native#sso-result=native-result',
    'https://cook.yunyoujun.cn/auth/callback?code=callback-code',
  ])('recognizes callback URLs and router paths alike: %s', (path) => {
    expect(isAuthCallbackUrl(path)).toBe(true)
  })

  it.each(['/home', '/auth/callback-other', '/auth/callback/other'])('keeps other paths deferred: %s', (path) => {
    expect(isAuthCallbackUrl(path)).toBe(false)
  })
})

function createScheduler() {
  const frames: (() => void)[] = []
  const idle: (() => void)[] = []
  let ready = () => {}
  return {
    ready: () => ready(),
    frames,
    idle,
    options: {
      href: 'https://cook.yunyoujun.cn/home',
      initialize: vi.fn(),
      onReady: (callback: () => void) => { ready = callback },
      scheduler: {
        setTimeout: (callback: () => void, delay: number) => window.setTimeout(callback, delay),
        clearTimeout: (id: number) => window.clearTimeout(id),
        requestAnimationFrame: vi.fn((callback: () => void) => frames.push(callback)),
        cancelAnimationFrame: vi.fn(),
        requestIdleCallback: vi.fn((callback: () => void) => idle.push(callback)),
        cancelIdleCallback: vi.fn(),
      },
    },
  }
}

describe('deferred auth initialization', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('leaves two rendering frames before starting work in an idle callback', () => {
    const scheduled = createScheduler()
    scheduleAuthInitialization(scheduled.options)
    expect(scheduled.frames).toHaveLength(0)

    scheduled.ready()
    scheduled.frames.shift()!()
    expect(scheduled.options.initialize).not.toHaveBeenCalled()
    expect(scheduled.idle).toHaveLength(0)

    scheduled.frames.shift()!()
    expect(scheduled.options.initialize).not.toHaveBeenCalled()
    scheduled.idle.shift()!()
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()

    vi.runAllTimers()
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()
  })

  it('restores identity within two seconds when a hidden page suspends rendering', () => {
    const scheduled = createScheduler()
    scheduleAuthInitialization(scheduled.options)
    scheduled.ready()

    vi.advanceTimersByTime(1999)
    expect(scheduled.options.initialize).not.toHaveBeenCalled()
    vi.advanceTimersByTime(1)
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()

    scheduled.frames.shift()!()
    expect(scheduled.idle).toHaveLength(0)
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()
  })

  it('has a fallback even when Nuxt readiness itself is waiting for an idle period', () => {
    const scheduled = createScheduler()
    scheduleAuthInitialization(scheduled.options)

    vi.advanceTimersByTime(2000)
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()

    scheduled.ready()
    expect(scheduled.frames).toHaveLength(0)
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()
  })

  it('does not require requestIdleCallback support', () => {
    const scheduled = createScheduler()
    scheduleAuthInitialization({
      ...scheduled.options,
      scheduler: { ...scheduled.options.scheduler, requestIdleCallback: undefined },
    })
    scheduled.ready()
    scheduled.frames.shift()!()
    expect(scheduled.options.initialize).not.toHaveBeenCalled()
    scheduled.frames.shift()!()
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()
    vi.runAllTimers()
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()
  })

  it('falls back to a timer if animation frames are unavailable', () => {
    const scheduled = createScheduler()
    scheduleAuthInitialization({
      ...scheduled.options,
      scheduler: { ...scheduled.options.scheduler, requestAnimationFrame: undefined },
    })
    scheduled.ready()
    vi.runAllTimers()
    expect(scheduled.options.initialize).toHaveBeenCalledOnce()
  })

  it('cancels queued work without leaving an idle or fallback duplicate', () => {
    const scheduled = createScheduler()
    const cancel = scheduleAuthInitialization(scheduled.options)
    scheduled.ready()
    scheduled.frames.shift()!()
    scheduled.frames.shift()!()
    cancel()

    scheduled.idle.shift()!()
    vi.runAllTimers()
    expect(scheduled.options.initialize).not.toHaveBeenCalled()
    expect(scheduled.options.scheduler.cancelIdleCallback).toHaveBeenCalledOnce()
  })
})
