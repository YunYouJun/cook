import type { Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, effectScope, ref, watch } from 'vue'

vi.unmock('vue')
const remote = vi.hoisted(() => ({ read: vi.fn(), write: vi.fn() }))
vi.mock('../apps/web/app/adapters/userData', () => ({ createCloudbaseUserDataRemote: () => remote }))

describe('account scope failure recovery', () => {
  let effects: ReturnType<typeof effectScope>
  let authenticated: Ref<boolean>
  let account: Ref<{ uid: string } | null>

  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers()
    localStorage.clear()
    effects = effectScope()
    authenticated = ref(true)
    account = ref({ uid: 'a' })
    const states = new Map<string, Ref<unknown>>()
    vi.stubGlobal('computed', computed)
    vi.stubGlobal('watch', watch)
    vi.stubGlobal('useState', <T>(key: string, create: () => T) => {
      if (!states.has(key))
        states.set(key, ref(create()))
      return states.get(key) as Ref<T>
    })
    vi.stubGlobal('useYunlefunAuth', () => ({ isAuthenticated: authenticated, account, callFunction: vi.fn() }))
    remote.read.mockReset().mockResolvedValue({ version: 1, favorites: [], favoriteRemovals: [], histories: [], historyRemovals: [], historyClearedAt: 0 })
    remote.write.mockReset().mockImplementation(async (_uid, document) => document)
  })

  afterEach(() => {
    effects.stop()
    vi.clearAllTimers()
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it.each(['b', ''])('fails closed on a quota error while selecting %s and can recover', async (next) => {
    const { useUserDataSync } = await import('../apps/web/app/composables/useUserDataSync')
    const { localUserData } = await import('../apps/web/app/composables/store/userData')
    const { favoriteEntries } = await import('../apps/web/app/composables/store/favorite')
    localUserData.writeLastUid('a')
    localUserData.write({ favorites: [{ id: 101, time: 1000 }], favoriteRemovals: [], histories: [], historyRemovals: [], historyClearedAt: 0 })
    const sync = effects.run(() => {
      const value = useUserDataSync()
      value.install()
      return value
    })!
    expect(sync.scopeRevision.value).toBe(1)
    const persist = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('quota', 'QuotaExceededError')
    })
    if (next)
      account.value = { uid: next }
    else
      authenticated.value = false
    expect(persist).toHaveBeenCalled()
    expect(sync.scopeBlocked.value).toBe(true)
    expect(favoriteEntries.value.map(item => item.id)).toEqual([101]) // Backing data survives, but App is unmounted.
    await vi.advanceTimersByTimeAsync(10_000)
    expect(remote.read).not.toHaveBeenCalled()
    persist.mockRestore()
    sync.syncNow()
    await vi.advanceTimersByTimeAsync(0)
    expect(sync.scopeBlocked.value).toBe(false)
    expect(localUserData.readLastUid()).toBe(next)
    expect(favoriteEntries.value).toEqual([])
    expect(JSON.parse(localStorage.getItem('cook:account-library:a')!).favorites.map((item: { id: number }) => item.id)).toEqual([101])
  })
})
