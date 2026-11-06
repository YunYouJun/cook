import type { Ref } from 'vue'
import { webcrypto } from 'node:crypto'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, defineComponent, h, ref, shallowRef, watch } from 'vue'

vi.unmock('vue')
const sdkCall = vi.hoisted(() => vi.fn())
vi.mock('@cloudbase/js-sdk', () => ({
  default: { init: () => ({ auth: () => ({}), callFunction: sdkCall }) },
}))

describe('cloudBase SDK sync failures', () => {
  let wrapper: ReturnType<typeof mount> | undefined

  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers()
    localStorage.clear()
    sdkCall.mockReset()
    vi.stubGlobal('crypto', webcrypto)
    vi.stubGlobal('computed', computed)
    vi.stubGlobal('watch', watch)
    const states = new Map<string, Ref<unknown>>()
    vi.stubGlobal('useState', <T>(key: string, create: () => T) => {
      if (!states.has(key))
        states.set(key, ref(create()))
      return states.get(key) as Ref<T>
    })
  })

  afterEach(() => {
    wrapper?.unmount()
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  async function openSyncStatus() {
    const { callCloudbaseFunction } = await import('../apps/web/app/adapters/yunlefunAuth')
    const { useUserDataSync } = await import('../apps/web/app/composables/useUserDataSync')
    vi.stubGlobal('useYunlefunAuth', () => ({
      isAuthenticated: shallowRef(true),
      account: ref({ uid: 'u1' }),
      callFunction: (name: string, data: Record<string, unknown>) => callCloudbaseFunction('test-environment', name, data),
    }))
    wrapper = mount(defineComponent({
      setup() {
        const sync = useUserDataSync()
        sync.install()
        return () => h('p', sync.errorMessage.value || sync.status.value)
      },
    }))
    return wrapper
  }

  it.each([
    ['MEMBERSHIP_REQUIRED', '跨平台同步需要有效的云乐坊会员，本机记录已保留'],
    ['LOGIN_REQUIRED', '请重新登录后同步，本机记录已保留'],
  ])('shows %s and stops automatic retries for a gateway-mode SDK response', async (code, message) => {
    // SDK 3.7.1 returns coded gateway failures without a `result` envelope.
    sdkCall.mockResolvedValue({ code, requestId: 'public-fixture' })
    const page = await openSyncStatus()
    await vi.waitFor(() => expect(page.text()).toBe(message))
    await vi.advanceTimersByTimeAsync(60_000)
    expect(sdkCall).toHaveBeenCalledTimes(1)
    expect(sdkCall).toHaveBeenCalledWith(expect.objectContaining({ data: expect.objectContaining({ action: 'capabilities' }) }))
    const { favoriteEntries } = await import('../apps/web/app/composables/store/favorite')
    expect(favoriteEntries.value).toEqual([])
  })

  it('retries a transient coded failure and reaches the synced state', async () => {
    sdkCall.mockResolvedValueOnce({ code: 'INTERNAL_ERROR' }).mockResolvedValue({
      result: { ok: true, protocolVersion: 2, document: { version: 1, favorites: [], favoriteRemovals: [], histories: [], historyRemovals: [], historyClearedAt: 0 } },
    })
    const page = await openSyncStatus()
    await vi.waitFor(() => expect(page.text()).toBe('云端同步失败'))
    await vi.advanceTimersByTimeAsync(1000)
    await vi.waitFor(() => expect(page.text()).toBe('synced'))
    expect(sdkCall).toHaveBeenCalledTimes(3)
  })
})
