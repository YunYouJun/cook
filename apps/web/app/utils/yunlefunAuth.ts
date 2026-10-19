import type {
  SsoAuthorizationResult,
  SsoRedirectResult,
} from '@yunlefun/sso'

export interface YunlefunAccount {
  avatarUrl?: string
  displayName: string
  uid: string
}

export type YunlefunAuthStatus
  = | 'checking'
    | 'error'
    | 'idle'
    | 'signed-in'
    | 'signed-out'
    | 'signing-in'

export interface YunlefunAuthSnapshot {
  account: YunlefunAccount | null
  errorMessage: string
  status: YunlefunAuthStatus
}

export type YunlefunAuthorizationResult
  = | SsoAuthorizationResult
    | Extract<SsoRedirectResult, { ok: false }>

export interface YunlefunAuthorizationAdapter {
  consumeInitial: () => Promise<YunlefunAuthorizationResult | null>
  start: () => Promise<YunlefunAuthorizationResult | null>
}

export interface YunlefunIdentityAdapter {
  adopt: (authorization: SsoAuthorizationResult) => Promise<YunlefunAccount | null>
  restore: () => Promise<YunlefunAccount | null>
  signOut: () => Promise<void>
}

export interface YunlefunAuthController {
  handleAuthorization: (authorization: YunlefunAuthorizationResult) => Promise<void>
  initialize: () => Promise<void>
  signIn: () => Promise<void>
  signOut: () => Promise<void>
  snapshot: () => Readonly<YunlefunAuthSnapshot>
}

interface YunlefunAuthControllerOptions {
  identity: YunlefunIdentityAdapter
  native: YunlefunAuthorizationAdapter
  platform: 'native' | 'web'
  web: YunlefunAuthorizationAdapter
}

export function createYunlefunAuthController(
  options: YunlefunAuthControllerOptions,
): YunlefunAuthController {
  const authorization = options.platform === 'native' ? options.native : options.web
  let initialized = false
  let state: YunlefunAuthSnapshot = {
    account: null,
    errorMessage: '',
    status: 'idle',
  }

  async function initialize(): Promise<void> {
    if (initialized)
      return

    initialized = true
    update({ status: 'checking' })

    try {
      const result = await authorization.consumeInitial()
      if (result) {
        if (!result.ok) {
          applyFailure(result.reason)
          return
        }

        await adopt(result)
        return
      }

      const account = await options.identity.restore()
      update({
        account,
        status: account ? 'signed-in' : 'signed-out',
      })
    }
    catch {
      update({
        account: null,
        errorMessage: '登录状态检查失败，请稍后重试',
        status: 'error',
      })
    }
  }

  async function signIn(): Promise<void> {
    update({
      errorMessage: '',
      status: 'signing-in',
    })
    try {
      const result = await authorization.start()
      if (result)
        await handleAuthorization(result)
    }
    catch {
      update({
        errorMessage: '无法打开登录授权，请稍后重试',
        status: 'error',
      })
    }
  }

  async function handleAuthorization(result: YunlefunAuthorizationResult): Promise<void> {
    if (!result.ok) {
      applyFailure(result.reason)
      return
    }

    try {
      await adopt(result)
    }
    catch {
      update({
        account: null,
        errorMessage: '登录身份验证失败，请重新授权',
        status: 'error',
      })
    }
  }

  async function signOut(): Promise<void> {
    try {
      await options.identity.signOut()
      update({
        account: null,
        errorMessage: '',
        status: 'signed-out',
      })
    }
    catch {
      update({
        errorMessage: '退出登录失败，请稍后重试',
        status: 'error',
      })
    }
  }

  async function adopt(result: SsoAuthorizationResult): Promise<void> {
    const account = await options.identity.adopt(result)
    if (!account)
      throw new Error('SSO identity is unavailable')

    update({
      account,
      errorMessage: '',
      status: 'signed-in',
    })
  }

  function applyFailure(reason: Extract<SsoRedirectResult, { ok: false }>['reason']): void {
    if (reason === 'not_authenticated') {
      update({
        account: null,
        errorMessage: '',
        status: 'signed-out',
      })
      return
    }

    const messages = {
      access_denied: '登录授权已取消',
      invalid_request: '登录请求无效，请刷新后重试',
      server_error: '登录服务响应异常，请稍后重试',
    } as const
    update({
      account: null,
      errorMessage: messages[reason],
      status: 'error',
    })
  }

  function update(patch: Partial<YunlefunAuthSnapshot>): void {
    state = { ...state, ...patch }
  }

  return {
    handleAuthorization,
    initialize,
    signIn,
    signOut,
    snapshot: () => Object.freeze({ ...state }),
  }
}
