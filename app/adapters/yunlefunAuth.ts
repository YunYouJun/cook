import type {
  AcquisitionContext,
  SsoAuthorizationResult,
} from '@yunlefun/sso'
import type {
  NativeSsoTransaction,
  NativeSsoTransactionStore,
} from '~/utils/nativeSso'
import type {
  YunlefunAccount,
  YunlefunAuthorizationAdapter,
  YunlefunAuthorizationResult,
  YunlefunIdentityAdapter,
} from '~/utils/yunlefunAuth'
import type { YunlefunSsoConfig } from '~/utils/yunlefunSsoConfig'
import { Browser } from '@capacitor/browser'
import { Preferences } from '@capacitor/preferences'
import {
  adoptSsoCode,
  consumeSsoRedirect,
  sanitizeAcquisitionContext,
  startSsoRedirect,
} from '@yunlefun/sso'
import {
  requestHostSsoAuthorization,
  SsoIdentityAdoptionError,
} from '@yunlefun/sso/browser'
import { readCookAcquisition } from '~/utils/acquisition'
import {
  consumeNativeSsoCallback,
  isNativeSsoCallbackUrl,
  openNativeSsoRedirect,
} from '~/utils/nativeSso'

const NATIVE_TRANSACTION_KEY = 'cook:sso:v3:native-transaction'

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
  signOut: () => Promise<{ error?: unknown } | void>
}

interface CloudbaseApp {
  auth: (options?: { persistence: 'session' }) => CloudbaseAuthClient
}

interface CloudbaseModule {
  default?: {
    init: (config: {
      auth: { detectSessionInUrl: false }
      env: string
      region: 'ap-shanghai'
    }) => CloudbaseApp
  }
  init?: (config: {
    auth: { detectSessionInUrl: false }
    env: string
    region: 'ap-shanghai'
  }) => CloudbaseApp
}

export interface NativeYunlefunAuthorizationAdapter extends YunlefunAuthorizationAdapter {
  cancel: () => Promise<void>
  consumeUrl: (url: string) => Promise<YunlefunAuthorizationResult | null>
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

export function createNativeYunlefunAuthorizationAdapter(
  config: YunlefunSsoConfig,
  initialUrl: () => Promise<string | null>,
  readAcquisition: () => AcquisitionContext | null = readCookAcquisition,
): NativeYunlefunAuthorizationAdapter {
  const store = createNativeTransactionStore()

  async function consumeUrl(url: string): Promise<YunlefunAuthorizationResult | null> {
    if (!isNativeSsoCallbackUrl(url, config.redirect.redirectUri))
      return null

    const result = await consumeNativeSsoCallback(url, store)
    await Browser.close().catch(() => undefined)
    return result
  }

  return {
    cancel: async () => store.clear(),
    consumeInitial: async () => {
      const url = await initialUrl()
      return url ? consumeUrl(url) : null
    },
    consumeUrl,
    start: async () => {
      const acquisition = readAcquisition()
      await openNativeSsoRedirect({
        ...config.redirect,
        prompt: 'consent',
        ...(acquisition ? { acquisition } : {}),
      }, store, async (url) => {
        await Browser.open({ url })
      })
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
      const auth = await loadAuth(config.cloudbaseEnv)
      return readAuthenticatedAccount(auth)
    },
    signOut: async () => {
      const auth = await loadAuth(config.cloudbaseEnv)
      const result = await auth.signOut()
      if (result?.error)
        throw new Error('CloudBase sign-out failed')
    },
  }
}

function createNativeTransactionStore(): NativeSsoTransactionStore {
  return {
    clear: async () => {
      await Preferences.remove({ key: NATIVE_TRANSACTION_KEY })
    },
    load: async () => {
      const { value } = await Preferences.get({ key: NATIVE_TRANSACTION_KEY })
      if (!value)
        return null

      try {
        return readNativeTransaction(JSON.parse(value) as unknown)
      }
      catch {
        return null
      }
    },
    save: async (transaction) => {
      await Preferences.set({
        key: NATIVE_TRANSACTION_KEY,
        value: JSON.stringify(transaction),
      })
    },
  }
}

async function getCloudbaseAuth(env: string): Promise<CloudbaseAuthClient> {
  if (cachedCloudbaseAuth) {
    if (cachedCloudbaseEnv !== env)
      throw new Error('CloudBase environment cannot change at runtime')
    return cachedCloudbaseAuth
  }

  const cloudbaseModule = await import('@cloudbase/js-sdk') as unknown as CloudbaseModule
  const cloudbase = cloudbaseModule.default ?? cloudbaseModule
  if (!cloudbase.init)
    throw new Error('@cloudbase/js-sdk does not expose init()')

  cachedCloudbaseEnv = env
  cachedCloudbaseAuth = cloudbase.init({
    auth: { detectSessionInUrl: false },
    env,
    region: 'ap-shanghai',
  }).auth({ persistence: 'session' })
  return cachedCloudbaseAuth
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

function readNativeTransaction(value: unknown): NativeSsoTransaction | null {
  if (!isRecord(value)
    || typeof value.clientId !== 'string'
    || typeof value.codeVerifier !== 'string'
    || typeof value.createdAt !== 'number'
    || typeof value.issuer !== 'string'
    || typeof value.nonce !== 'string'
    || typeof value.redirectUri !== 'string'
    || !Array.isArray(value.scope)
    || !value.scope.every(scope => typeof scope === 'string')) {
    return null
  }

  const acquisition = sanitizeAcquisitionContext(value.acquisition)
  return {
    clientId: value.clientId,
    codeVerifier: value.codeVerifier,
    createdAt: value.createdAt,
    issuer: value.issuer,
    nonce: value.nonce,
    redirectUri: value.redirectUri,
    scope: value.scope,
    ...(acquisition ? { acquisition } : {}),
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}
