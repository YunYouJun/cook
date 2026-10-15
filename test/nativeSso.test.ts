import type {
  NativeSsoTransaction,
  NativeSsoTransactionStore,
} from '../app/utils/nativeSso'
import { encodeSsoRedirectResult } from '@yunlefun/sso'
import { describe, expect, it } from 'vitest'
import {
  beginNativeSsoRedirect,
  consumeNativeSsoCallback,
  openNativeSsoRedirect,
  stripNativeSsoCallbackHash,
} from '../app/utils/nativeSso'

class MemoryTransactionStore implements NativeSsoTransactionStore {
  transaction: NativeSsoTransaction | null = null

  async clear(): Promise<void> {
    this.transaction = null
  }

  async load(): Promise<NativeSsoTransaction | null> {
    return this.transaction
  }

  async save(transaction: NativeSsoTransaction): Promise<void> {
    this.transaction = transaction
  }
}

const options = {
  clientId: 'cook-mobile',
  redirectUri: 'https://cook.yunyoujun.cn/auth/callback?platform=native',
  scope: ['identity:bootstrap'],
  ssoOrigin: 'https://www.yunle.fun',
} as const

describe('native SSO redirect', () => {
  it('retains only valid optional acquisition through the native PKCE callback', async () => {
    const touch = {
      capturedAt: 1000,
      landingHost: 'cook.yunle.fun',
      landingPath: '/random',
      referrerHost: 'www.baidu.com',
      utmSource: '',
      utmMedium: '',
      utmCampaign: '',
    }
    const acquisition = { version: 1 as const, firstTouch: touch, registerTouch: touch }
    const store = new MemoryTransactionStore()
    const url = await beginNativeSsoRedirect({ ...options, acquisition }, store, {
      now: () => 1000,
      randomBase64Url: sequence('n'.repeat(43), 'v'.repeat(43)),
    })
    expect(JSON.parse(new URL(url).searchParams.get('acquisition')!)).toEqual(acquisition)
    const result = await consumeNativeSsoCallback(callbackUrl({
      code: 'c'.repeat(43),
      issuer: 'https://www.yunle.fun',
      nonce: 'n'.repeat(43),
    }), store, { now: () => 2000 })
    expect(result).toMatchObject({ ok: true, acquisition })
  })

  it('accepts a matching claimed HTTPS callback exactly once', async () => {
    const store = new MemoryTransactionStore()
    const authorizationUrl = await beginNativeSsoRedirect(options, store, {
      now: () => 1_000,
      randomBase64Url: sequence('n'.repeat(43), 'v'.repeat(43)),
    })
    const request = new URL(authorizationUrl)

    expect(request.origin + request.pathname).toBe('https://www.yunle.fun/auth/sso')
    expect(request.searchParams.get('client_id')).toBe('cook-mobile')
    expect(request.searchParams.get('redirect_uri')).toBe(options.redirectUri)
    expect(request.searchParams.get('nonce')).toBe('n'.repeat(43))
    expect(request.searchParams.get('code_challenge_method')).toBe('S256')

    const callback = callbackUrl({
      code: 'c'.repeat(43),
      issuer: 'https://www.yunle.fun',
      nonce: 'n'.repeat(43),
    })

    await expect(consumeNativeSsoCallback(callback, store, { now: () => 2_000 })).resolves.toEqual({
      ok: true,
      clientId: 'cook-mobile',
      code: 'c'.repeat(43),
      codeVerifier: 'v'.repeat(43),
      issuer: 'https://www.yunle.fun',
      nonce: 'n'.repeat(43),
      redirectUri: options.redirectUri,
      scope: ['identity:bootstrap'],
    })
    await expect(consumeNativeSsoCallback(callback, store, { now: () => 2_000 })).resolves.toBeNull()
  })

  it('rejects an altered callback path and consumes the pending transaction', async () => {
    const store = new MemoryTransactionStore()
    await beginNativeSsoRedirect(options, store, {
      now: () => 1_000,
      randomBase64Url: sequence('n'.repeat(43), 'v'.repeat(43)),
    })
    const callback = callbackUrl({
      code: 'c'.repeat(43),
      issuer: 'https://www.yunle.fun',
      nonce: 'n'.repeat(43),
    }).replace('/auth/callback', '/evil/callback')

    await expect(consumeNativeSsoCallback(callback, store, { now: () => 2_000 })).resolves.toBeNull()
    await expect(consumeNativeSsoCallback(callbackUrl({
      code: 'c'.repeat(43),
      issuer: 'https://www.yunle.fun',
      nonce: 'n'.repeat(43),
    }), store, { now: () => 2_000 })).resolves.toBeNull()
  })

  it('rejects an expired transaction', async () => {
    const store = new MemoryTransactionStore()
    await beginNativeSsoRedirect(options, store, {
      now: () => 1_000,
      randomBase64Url: sequence('n'.repeat(43), 'v'.repeat(43)),
    })

    await expect(consumeNativeSsoCallback(callbackUrl({
      code: 'c'.repeat(43),
      issuer: 'https://www.yunle.fun',
      nonce: 'n'.repeat(43),
    }), store, { now: () => 601_001 })).resolves.toBeNull()
  })

  it('clears the pending transaction when the system browser cannot open', async () => {
    const store = new MemoryTransactionStore()

    await expect(openNativeSsoRedirect(options, store, async () => {
      throw new Error('browser unavailable')
    }, {
      now: () => 1_000,
      randomBase64Url: sequence('n'.repeat(43), 'v'.repeat(43)),
    })).rejects.toThrow('browser unavailable')

    expect(store.transaction).toBeNull()
  })

  it('removes authorization data from a browser fallback URL', () => {
    expect(stripNativeSsoCallbackHash(
      `${options.redirectUri}#ylf_sso=sensitive-authorization-result`,
    )).toBe('/auth/callback?platform=native')
  })
})

function callbackUrl(input: { code: string, issuer: string, nonce: string }): string {
  const result = encodeSsoRedirectResult({
    code: input.code,
    issuer: input.issuer,
    nonce: input.nonce,
    ok: true,
  })
  return `${options.redirectUri}#ylf_sso=${result}`
}

function sequence(...values: string[]): () => string {
  let index = 0
  return () => values[index++] ?? ''
}
