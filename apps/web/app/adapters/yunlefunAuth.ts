import type {
  AcquisitionContext,
  SsoAuthorizationResult,
} from '@yunlefun/sso'
import type { CloudbaseRuntime } from './cloudbase'
import type {
  YunlefunAccount,
  YunlefunAuthorizationAdapter,
  YunlefunIdentityAdapter,
} from '~/utils/yunlefunAuth'
import type { YunlefunSsoConfig } from '~/utils/yunlefunSsoConfig'
import {
  adoptSsoCode,
  consumeSsoRedirect,
  startSsoRedirect,
} from '@yunlefun/sso'
import {
  requestHostSsoAuthorization,
  SsoIdentityAdoptionError,
} from '@yunlefun/sso/browser'
import { readCookAcquisition } from '~/utils/acquisition'

interface CloudbaseUser {
  avatar_url?: string
  email?: string
  id?: string
  is_anonymous?: boolean
  name?: string
  phone?: string
  phone_number?: string
  picture?: string
  sub?: string
  uid?: string
  user_metadata?: Record<string, unknown>
  username?: string
}

interface CloudbaseSession {
  access_token?: string
  user?: CloudbaseUser
}

export interface CloudbaseAuthClient {
  getSession: () => Promise<{
    data?: { session?: CloudbaseSession | null }
    error?: unknown
  }>
  getUser: () => Promise<{
    data?: { user?: CloudbaseUser | null }
    error?: unknown
  }>
  signInWithCustomTicket: (getTicket: () => Promise<string>) => Promise<unknown>
  signOut: () => Promise<unknown>
}

/** Calls a cloud function with the signed-in session and resolves its `result`. */
export type CloudbaseFunctionCaller = (name: string, data: Record<string, unknown>) => Promise<unknown>

export interface CloudbaseApp {
  auth: (options?: { persistence: 'session' }) => CloudbaseAuthClient
  callFunction: (options: { name: string, data: Record<string, unknown> }) => Promise<{
    code?: string
    errCode?: number | string
    errMsg?: string
    result?: unknown
  } | undefined>
}

export interface CloudbaseIdentityDependencies {
  getAuth?: (env: string) => Promise<CloudbaseAuthClient>
}

export interface WebYunlefunAuthorizationDependencies {
  requestHostAuthorization?: typeof requestHostSsoAuthorization
  startRedirect?: typeof startSsoRedirect
  readAcquisition?: () => AcquisitionContext | null
}

let cachedCloudbaseEnv = ''
let cachedCloudbase: Promise<CloudbaseRuntime> | undefined
let cachedCloudbaseAuth: CloudbaseAuthClient | undefined

export function createWebYunlefunAuthorizationAdapter(
  config: YunlefunSsoConfig,
  dependencies: WebYunlefunAuthorizationDependencies = {},
): YunlefunAuthorizationAdapter {
  const requestHostAuthorization
    = dependencies.requestHostAuthorization ?? requestHostSsoAuthorization
  const startRedirect = dependencies.startRedirect ?? startSsoRedirect

  return {
    consumeInitial: async () => consumeSsoRedirect(),
    start: async () => {
      const acquisition = (dependencies.readAcquisition ?? readCookAcquisition)()
      const redirect = { ...config.redirect, ...(acquisition ? { acquisition } : {}) }
      try {
        const authorization = await requestHostAuthorization({
          ...redirect,
          prompt: 'consent',
        })
        if (authorization)
          return authorization
      }
      catch (error) {
        if (error instanceof SsoIdentityAdoptionError
          && error.reason === 'access_denied') {
          return {
            ok: false,
            reason: 'access_denied',
          }
        }
        throw error
      }

      await startRedirect(redirect)
      return null
    },
  }
}

export function createCloudbaseIdentityAdapter(
  config: YunlefunSsoConfig,
  dependencies: CloudbaseIdentityDependencies = {},
): YunlefunIdentityAdapter {
  const loadAuth = dependencies.getAuth ?? getCloudbaseAuth
  return {
    adopt: async (authorization: SsoAuthorizationResult) => {
      const auth = await loadAuth(config.cloudbaseEnv)
      const adopted = await adoptSsoCode(auth, authorization, config.exchange)
      return adopted ? readAuthenticatedAccount(auth) : null
    },
    restore: async () => {
      if (!hasStoredCloudbaseSession(config.cloudbaseEnv))
        return null
      const auth = await loadAuth(config.cloudbaseEnv)
      return readAuthenticatedAccount(auth)
    },
    signOut: async () => {
      const auth = await loadAuth(config.cloudbaseEnv)
      const result = await auth.signOut()
      if (result && typeof result === 'object' && 'error' in result && result.error)
        throw new Error('CloudBase sign-out failed')
    },
  }
}

function hasStoredCloudbaseSession(env: string): boolean {
  // CloudBase v3 uses its environment ID as the default OAuth client ID.
  // A stored value only triggers SDK loading. The SDK validates the session.
  const key = `credentials_${env}`
  try {
    return window.localStorage.getItem(key) !== null || window.sessionStorage.getItem(key) !== null
  }
  catch {
    // Storage can be restricted by the browser. Let the SDK handle that case.
    return true
  }
}

function getCloudbase(env: string): Promise<CloudbaseRuntime> {
  if (cachedCloudbase) {
    if (cachedCloudbaseEnv !== env)
      return Promise.reject(new Error('CloudBase environment cannot change at runtime'))
    return cachedCloudbase
  }

  cachedCloudbaseEnv = env
  cachedCloudbase = (async () => {
    const { initCloudbase } = await import('./cloudbase')
    return initCloudbase(env)
  })()
  cachedCloudbase.catch(() => {
    cachedCloudbase = undefined
  })
  return cachedCloudbase
}

async function getCloudbaseAuth(env: string): Promise<CloudbaseAuthClient> {
  const { app } = await getCloudbase(env)
  cachedCloudbaseAuth ??= app.auth({ persistence: 'session' })
  return cachedCloudbaseAuth
}

/** Call a cloud function on the same CloudBase app that holds the SSO session. */
export async function callCloudbaseFunction(
  env: string,
  name: string,
  data: Record<string, unknown>,
): Promise<unknown> {
  // Initialise auth first so the call carries the signed-in session.
  await getCloudbaseAuth(env)
  const { app, loadFunctions } = await getCloudbase(env)
  await loadFunctions()
  const response = await app.callFunction({ name, data })
  // A missing result means the call itself failed (not deployed, crashed, …).
  if (!response || response.result === undefined) {
    // Gateway-mode SDK calls put coded failures outside the result envelope.
    const code = response?.code ?? response?.errCode
    throw Object.assign(new Error(`CloudBase function ${name} failed: ${code ?? response?.errMsg ?? 'no result'}`), { code })
  }
  return response.result
}

async function readAuthenticatedAccount(auth: CloudbaseAuthClient): Promise<YunlefunAccount | null> {
  const sessionResult = await auth.getSession()
  const session = sessionResult.data?.session
  if (sessionResult.error
    || !session
    || typeof session.access_token !== 'string'
    || session.access_token.length < 16
    || session.user?.is_anonymous) {
    return null
  }

  const userResult = await auth.getUser()
  if (userResult.error)
    return null
  return normalizeAccount(userResult.data?.user ?? session.user)
}

function normalizeAccount(user: CloudbaseUser | null | undefined): YunlefunAccount | null {
  const uid = firstString(user?.id, user?.uid, user?.sub)
  if (!user || user.is_anonymous || !uid)
    return null

  const metadata = user.user_metadata ?? {}
  const displayName = firstString(
    metadata.displayName,
    metadata.name,
    metadata.nickName,
    metadata.username,
    user.name,
    user.username,
    user.email,
    user.phone,
    user.phone_number,
    uid.slice(0, 8),
  )
  const avatarUrl = firstString(
    metadata.avatarUrl,
    metadata.avatar_url,
    metadata.picture,
    user.avatar_url,
    user.picture,
  )

  return {
    uid,
    displayName: displayName ?? uid.slice(0, 8),
    ...(avatarUrl ? { avatarUrl } : {}),
  }
}

function firstString(...values: unknown[]): string | undefined {
  return values.find(value => typeof value === 'string' && value.trim())?.toString().trim()
}
