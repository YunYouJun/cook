import { describe, expect, it, vi } from 'vitest'
import { isPrivateAnalyticsUrl, resolveClarityConfig, setCookEventTracker, trackCookEvent } from '../apps/web/app/utils/analytics'

const config = { projectId: 'ks1ebw03i5', domains: 'cook.yunyoujun.cn, cook.yunle.fun' }

describe('clarity configuration', () => {
  it('only enables valid project IDs on allowed production web hosts', () => {
    expect(resolveClarityConfig(config, 'https://cook.yunle.fun/', false)).toEqual({ projectId: config.projectId })
    expect(resolveClarityConfig(config, 'https://cook.yunyoujun.cn/', true)).toBeNull()
    expect(resolveClarityConfig(config, 'https://preview.example.com/', false)).toBeNull()
    expect(resolveClarityConfig(config, 'capacitor://cook.yunle.fun/', false)).toBeNull()
    expect(resolveClarityConfig({ ...config, projectId: '' }, 'https://cook.yunle.fun/', false)).toBeNull()
    expect(resolveClarityConfig({ ...config, projectId: '../invalid' }, 'https://cook.yunle.fun/', false)).toBeNull()
  })

  it.each(['/auth/callback', '/?ticket=secret', '/?%74icket=secret', '/?code=secret', '/#access_token=secret'])('does not start a recorder on %s', (path) => {
    expect(resolveClarityConfig(config, `https://cook.yunle.fun${path}`, false)).toBeNull()
  })

  it('allows ordinary campaign and tab URLs', () => {
    expect(isPrivateAnalyticsUrl('https://cook.yunle.fun/tabs/home?utm_source=github')).toBe(false)
  })
})

describe('cook events', () => {
  it('sends action names and isolates both synchronous and asynchronous failures', async () => {
    const send = vi.fn()
    setCookEventTracker(send)
    trackCookEvent('click_stuff')
    expect(send).toHaveBeenCalledExactlyOnceWith('click_stuff')
    send.mockImplementationOnce(() => {
      throw new Error('blocked')
    })
    expect(() => trackCookEvent('click_tool')).not.toThrow()
    send.mockRejectedValueOnce(new Error('offline'))
    trackCookEvent('click_dish')
    await Promise.resolve()
  })
})
