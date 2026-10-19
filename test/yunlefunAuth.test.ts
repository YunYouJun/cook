import type { SsoAuthorizationResult } from '@yunlefun/sso'
import type {
  YunlefunAccount,
  YunlefunAuthorizationAdapter,
  YunlefunIdentityAdapter,
} from '../apps/web/app/utils/yunlefunAuth'
import { describe, expect, it } from 'vitest'
import {
  createYunlefunAuthController,
} from '../apps/web/app/utils/yunlefunAuth'

const authorization: SsoAuthorizationResult = {
  ok: true,
  clientId: 'cook-web',
  code: 'c'.repeat(43),
  codeVerifier: 'v'.repeat(43),
  issuer: 'https://www.yunle.fun',
  nonce: 'n'.repeat(43),
  redirectUri: 'https://cook.yunyoujun.cn/auth/callback',
  scope: ['identity:bootstrap'],
}

const account: YunlefunAccount = {
  displayName: '云游君',
  uid: 'user-1',
}

describe('yunlefun auth controller', () => {
  it('adopts an initial authorization result through its public state', async () => {
    const web = fakeAuthorizationAdapter(authorization)
    const identity = fakeIdentityAdapter()
    const controller = createYunlefunAuthController({
      identity,
      native: fakeAuthorizationAdapter(),
      platform: 'web',
      web,
    })

    await controller.initialize()

    expect(controller.snapshot()).toEqual({
      account,
      errorMessage: '',
      status: 'signed-in',
    })
    expect(identity.adopted).toEqual([authorization])
  })

  it('opens the platform-appropriate authorization surface', async () => {
    const web = fakeAuthorizationAdapter()
    const native = fakeAuthorizationAdapter()
    const webController = createYunlefunAuthController({
      identity: fakeIdentityAdapter(),
      native,
      platform: 'web',
      web,
    })
    const nativeController = createYunlefunAuthController({
      identity: fakeIdentityAdapter(),
      native,
      platform: 'native',
      web,
    })

    await webController.signIn()
    await nativeController.signIn()

    expect(web.started).toBe(1)
    expect(native.started).toBe(1)
    expect(webController.snapshot().status).toBe('signing-in')
    expect(nativeController.snapshot().status).toBe('signing-in')
  })

  it('adopts a host authorization without leaving the current page', async () => {
    const web = fakeAuthorizationAdapter(null, authorization)
    const identity = fakeIdentityAdapter()
    const controller = createYunlefunAuthController({
      identity,
      native: fakeAuthorizationAdapter(),
      platform: 'web',
      web,
    })

    await controller.signIn()

    expect(identity.adopted).toEqual([authorization])
    expect(controller.snapshot()).toEqual({
      account,
      errorMessage: '',
      status: 'signed-in',
    })
  })

  it('clears both the identity session and public account on sign-out', async () => {
    const identity = fakeIdentityAdapter(account)
    const controller = createYunlefunAuthController({
      identity,
      native: fakeAuthorizationAdapter(),
      platform: 'web',
      web: fakeAuthorizationAdapter(),
    })
    await controller.initialize()

    await controller.signOut()

    expect(identity.signOutCount).toBe(1)
    expect(controller.snapshot()).toEqual({
      account: null,
      errorMessage: '',
      status: 'signed-out',
    })
  })

  it('keeps the authenticated account visible when sign-out fails', async () => {
    const identity = fakeIdentityAdapter(account)
    identity.signOut = async () => {
      identity.signOutCount += 1
      throw new Error('remote sign-out failed')
    }
    const controller = createYunlefunAuthController({
      identity,
      native: fakeAuthorizationAdapter(),
      platform: 'web',
      web: fakeAuthorizationAdapter(),
    })
    await controller.initialize()

    await expect(controller.signOut()).resolves.toBeUndefined()

    expect(controller.snapshot()).toEqual({
      account,
      errorMessage: '退出登录失败，请稍后重试',
      status: 'error',
    })
  })

  it('adopts an authorization delivered after the native app resumes', async () => {
    const identity = fakeIdentityAdapter()
    const controller = createYunlefunAuthController({
      identity,
      native: fakeAuthorizationAdapter(),
      platform: 'native',
      web: fakeAuthorizationAdapter(),
    })
    await controller.initialize()

    await controller.handleAuthorization(authorization)

    expect(identity.adopted).toEqual([authorization])
    expect(controller.snapshot().status).toBe('signed-in')
  })
})

function fakeAuthorizationAdapter(
  initial: SsoAuthorizationResult | null = null,
  startedResult: SsoAuthorizationResult | null = null,
): YunlefunAuthorizationAdapter & { started: number } {
  return {
    started: 0,
    async consumeInitial() {
      return initial
    },
    async start() {
      this.started += 1
      return startedResult
    },
  }
}

function fakeIdentityAdapter(restored: YunlefunAccount | null = null): YunlefunIdentityAdapter & {
  adopted: SsoAuthorizationResult[]
  signOutCount: number
} {
  return {
    adopted: [],
    signOutCount: 0,
    async adopt(nextAuthorization) {
      this.adopted.push(nextAuthorization)
      return account
    },
    async restore() {
      return restored
    },
    async signOut() {
      this.signOutCount += 1
    },
  }
}
