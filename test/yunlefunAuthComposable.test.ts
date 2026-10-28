import type { Ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { computed, readonly, ref } from 'vue'

const adapters = vi.hoisted(() => ({
  consumeInitial: vi.fn(),
  start: vi.fn(),
  restore: vi.fn(),
  adopt: vi.fn(),
  signOut: vi.fn(),
}))

vi.mock('../apps/web/app/adapters/yunlefunAuth', () => ({
  callCloudbaseFunction: vi.fn(),
  createCloudbaseIdentityAdapter: () => ({
    restore: adapters.restore,
    adopt: adapters.adopt,
    signOut: adapters.signOut,
  }),
  createWebYunlefunAuthorizationAdapter: () => ({
    consumeInitial: adapters.consumeInitial,
    start: adapters.start,
  }),
}))

vi.mock('../apps/web/app/utils/yunlefunSsoConfig', () => ({
  resolveYunlefunSsoConfig: () => ({
    ok: true,
    config: {
      cloudbaseEnv: 'test-environment',
      exchange: { exchangeUrl: 'https://example.com/sso-ticket' },
      redirect: {
        clientId: 'cook-web',
        redirectUri: 'https://cook.yunyoujun.cn/auth/callback',
        scope: ['identity:bootstrap'],
        ssoOrigin: 'https://www.yunle.fun',
      },
    },
  }),
}))

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

describe('yunlefun auth initialization at the composable boundary', () => {
  const navigate = vi.fn(async () => {})

  beforeEach(() => {
    vi.resetModules()
    adapters.consumeInitial.mockReset().mockResolvedValue(null)
    adapters.start.mockReset().mockResolvedValue(null)
    adapters.restore.mockReset().mockResolvedValue(null)
    adapters.adopt.mockReset().mockResolvedValue({ uid: 'test-user', displayName: 'Test' })
    adapters.signOut.mockReset().mockResolvedValue(undefined)
    navigate.mockClear()
    window.history.replaceState({}, '', '/home')

    const state = new Map<string, Ref<unknown>>()
    vi.stubGlobal('useState', <T>(key: string, create: () => T) => {
      if (!state.has(key))
        state.set(key, ref(create()))
      return state.get(key) as Ref<T>
    })
    vi.stubGlobal('useRuntimeConfig', () => ({ public: {} }))
    vi.stubGlobal('computed', computed)
    vi.stubGlobal('readonly', readonly)
    vi.stubGlobal('navigateTo', navigate)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    window.history.replaceState({}, '', '/')
  })

  it('initializes before a user signs in ahead of deferred startup', async () => {
    const { useYunlefunAuth } = await import('../apps/web/app/composables/useYunlefunAuth')
    const auth = useYunlefunAuth()

    await auth.signIn()

    expect(adapters.restore).toHaveBeenCalledOnce()
    expect(adapters.start).toHaveBeenCalledOnce()
    expect(auth.status.value).toBe('signing-in')
  })

  it('shares an in-flight restore between startup and a login click', async () => {
    const restored = deferred<null>()
    adapters.restore.mockReturnValue(restored.promise)
    const { useYunlefunAuth } = await import('../apps/web/app/composables/useYunlefunAuth')
    const auth = useYunlefunAuth()
    const startup = auth.initialize()
    const login = auth.signIn()
    await vi.waitFor(() => expect(adapters.restore).toHaveBeenCalledOnce())

    expect(auth.status.value).toBe('checking')
    expect(adapters.start).not.toHaveBeenCalled()
    restored.resolve(null)
    await Promise.all([startup, login])

    expect(adapters.restore).toHaveBeenCalledOnce()
    expect(adapters.start).toHaveBeenCalledOnce()
  })

  it('publishes the busy state while host or redirect authorization is pending', async () => {
    const authorization = deferred<null>()
    adapters.start.mockReturnValue(authorization.promise)
    const { useYunlefunAuth } = await import('../apps/web/app/composables/useYunlefunAuth')
    const auth = useYunlefunAuth()
    const login = auth.signIn()
    await vi.waitFor(() => expect(adapters.start).toHaveBeenCalledOnce())

    expect(auth.status.value).toBe('signing-in')
    authorization.resolve(null)
    await login
  })

  it('adopts a Web callback without first restoring a session', async () => {
    window.history.replaceState({}, '', '/auth/callback?code=callback-code')
    const result = { ok: true, code: 'callback-code' }
    adapters.consumeInitial.mockResolvedValue(result)
    const { useYunlefunAuth } = await import('../apps/web/app/composables/useYunlefunAuth')
    const auth = useYunlefunAuth()

    await Promise.all([auth.initialize(), auth.initialize()])

    expect(adapters.consumeInitial).toHaveBeenCalledOnce()
    expect(adapters.adopt).toHaveBeenCalledExactlyOnceWith(result)
    expect(adapters.restore).not.toHaveBeenCalled()
    expect(auth.isAuthenticated.value).toBe(true)
    expect(navigate).toHaveBeenCalledWith('/my', { replace: true })
  })

  it('never adopts a native callback, including a trailing slash and fragment', async () => {
    window.history.replaceState({}, '', '/auth/callback/?platform=native#sso-result=native-result')
    const { useYunlefunAuth } = await import('../apps/web/app/composables/useYunlefunAuth')
    const auth = useYunlefunAuth()

    await auth.initialize()

    expect(adapters.consumeInitial).not.toHaveBeenCalled()
    expect(adapters.restore).not.toHaveBeenCalled()
    expect(adapters.adopt).not.toHaveBeenCalled()
    expect(auth.status.value).toBe('signed-out')
    expect(auth.configurationMessage.value).toContain('请在应用中重新授权')
    expect(window.location.hash).toBe('')
    expect(navigate).not.toHaveBeenCalled()
  })
})
