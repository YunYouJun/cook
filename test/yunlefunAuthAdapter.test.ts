import type { AcquisitionContext, SsoAuthorizationResult } from '@yunlefun/sso'
import type { YunlefunSsoConfig } from '../apps/web/app/utils/yunlefunSsoConfig'
import { SsoIdentityAdoptionError } from '@yunlefun/sso/browser'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createCloudbaseIdentityAdapter,
  createWebYunlefunAuthorizationAdapter,
} from '../apps/web/app/adapters/yunlefunAuth'

const config: YunlefunSsoConfig = {
  cloudbaseEnv: 'yunlefun-production',
  exchange: {
    exchangeUrl: 'https://api.yunle.fun/sso-ticket',
  },
  redirect: {
    clientId: 'cook-web',
    redirectUri: 'https://cook.yunyoujun.cn/auth/callback',
    scope: ['identity:bootstrap'],
    ssoOrigin: 'https://www.yunle.fun',
  },
}

const hostAuthorization: SsoAuthorizationResult = {
  ok: true,
  clientId: 'cook-web',
  code: 'c'.repeat(43),
  codeVerifier: 'v'.repeat(43),
  issuer: 'https://www.yunle.fun',
  nonce: 'n'.repeat(43),
  redirectUri: 'https://cook.yunyoujun.cn/auth/callback',
  scope: ['identity:bootstrap'],
}

const touch = {
  capturedAt: 1000,
  landingHost: 'cook.yunle.fun',
  landingPath: '/random',
  referrerHost: 'www.baidu.com',
  utmSource: 'baidu',
  utmMedium: 'organic',
  utmCampaign: '',
}
const acquisition: AcquisitionContext = { version: 1, firstTouch: touch, registerTouch: touch }

describe('web authorization adapter', () => {
  it('passes the same captured context to host authorization and redirect fallback', async () => {
    const requestHostAuthorization = vi.fn(async () => null)
    const startRedirect = vi.fn()
    const adapter = createWebYunlefunAuthorizationAdapter(config, {
      readAcquisition: () => acquisition,
      requestHostAuthorization,
      startRedirect,
    })
    await adapter.start()
    expect(requestHostAuthorization).toHaveBeenCalledWith({ ...config.redirect, prompt: 'consent', acquisition })
    expect(startRedirect).toHaveBeenCalledWith({ ...config.redirect, acquisition })
  })

  it('uses the YunLeFun host consent sheet without starting a redirect', async () => {
    const requestHostAuthorization = vi.fn(async () => hostAuthorization)
    const startRedirect = vi.fn()
    const adapter = createWebYunlefunAuthorizationAdapter(config, {
      requestHostAuthorization,
      startRedirect,
    })

    await expect(adapter.start()).resolves.toEqual(hostAuthorization)
    expect(requestHostAuthorization).toHaveBeenCalledWith({
      ...config.redirect,
      prompt: 'consent',
    })
    expect(startRedirect).not.toHaveBeenCalled()
  })

  it('falls back to the top-level web redirect outside the YunLeFun host', async () => {
    const startRedirect = vi.fn()
    const adapter = createWebYunlefunAuthorizationAdapter(config, {
      requestHostAuthorization: async () => null,
      startRedirect,
    })

    await expect(adapter.start()).resolves.toBeNull()
    expect(startRedirect).toHaveBeenCalledWith(config.redirect)
  })

  it('does not bypass an explicit host denial with a web redirect', async () => {
    const startRedirect = vi.fn()
    const adapter = createWebYunlefunAuthorizationAdapter(config, {
      requestHostAuthorization: async () => {
        throw new SsoIdentityAdoptionError(
          'Host authorization was denied',
          'access_denied',
        )
      },
      startRedirect,
    })

    await expect(adapter.start()).resolves.toEqual({
      ok: false,
      reason: 'access_denied',
    })
    expect(startRedirect).not.toHaveBeenCalled()
  })
})

describe('cloudbase identity adapter', () => {
  const sessionKey = `credentials_${config.cloudbaseEnv}`
  const getSession = vi.fn(async () => ({ data: { session: null } }))
  const getAuth = vi.fn(async () => ({
    getSession,
    getUser: async () => ({ data: { user: null } }),
    signInWithCustomTicket: async () => ({}),
    signOut: async () => {},
  }))

  beforeEach(() => {
    localStorage.removeItem(sessionKey)
    sessionStorage.removeItem(sessionKey)
    getAuth.mockClear()
    getSession.mockClear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.removeItem(sessionKey)
    sessionStorage.removeItem(sessionKey)
  })

  it('skips SDK loading when the browser has no CloudBase credentials', async () => {
    const identity = createCloudbaseIdentityAdapter(config, { getAuth })
    await expect(identity.restore()).resolves.toBeNull()
    expect(getAuth).not.toHaveBeenCalled()
  })

  it.each(['localStorage', 'sessionStorage'] as const)('restores older sessions from %s using the SDK', async (storage) => {
    window[storage].setItem(sessionKey, 'legacy session fixture')
    const identity = createCloudbaseIdentityAdapter(config, { getAuth })
    await expect(identity.restore()).resolves.toBeNull()
    expect(getAuth).toHaveBeenCalledExactlyOnceWith(config.cloudbaseEnv)
    expect(getSession).toHaveBeenCalledOnce()
  })

  it('lets the SDK handle browsers that block storage reads', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementationOnce(() => {
      throw new DOMException('Storage unavailable', 'SecurityError')
    })
    const identity = createCloudbaseIdentityAdapter(config, { getAuth })
    await expect(identity.restore()).resolves.toBeNull()
    expect(getAuth).toHaveBeenCalledExactlyOnceWith(config.cloudbaseEnv)
  })

  it('rejects a sign-out response that contains a CloudBase error', async () => {
    const identity = createCloudbaseIdentityAdapter(config, {
      getAuth: async () => ({
        getSession: async () => ({ data: { session: null } }),
        getUser: async () => ({ data: { user: null } }),
        signInWithCustomTicket: async () => ({}),
        signOut: async () => ({ error: new Error('remote sign-out failed') }),
      }),
    })

    await expect(identity.signOut()).rejects.toThrow('CloudBase sign-out failed')
  })
})
