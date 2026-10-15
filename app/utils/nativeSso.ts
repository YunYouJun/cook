import type {
  AcquisitionContext,
  RedirectSsoOptions,
  SsoAuthorizationResult,
  SsoRedirectResult,
} from '@yunlefun/sso'
import {
  buildSsoRequestUrl,
  deriveSsoPkceChallenge,
  isSsoPkceVerifier,
  parseSsoRedirectResult,
  readSsoClientId,
  readSsoRedirectUri,
  readSsoScope,
  sanitizeAcquisitionContext,
  YLF_DEFAULT_SSO_ORIGIN,
} from '@yunlefun/sso'

const TRANSACTION_TTL_MS = 10 * 60 * 1000
const NONCE_RE = /^[\w-]{32,128}$/
const BASE64_PADDING_RE = /=+$/

export interface NativeSsoTransaction {
  clientId: string
  codeVerifier: string
  createdAt: number
  issuer: string
  nonce: string
  redirectUri: string
  scope: string[]
  acquisition?: AcquisitionContext
}

export interface NativeSsoTransactionStore {
  clear: () => Promise<void>
  load: () => Promise<NativeSsoTransaction | null>
  save: (transaction: NativeSsoTransaction) => Promise<void>
}

export interface NativeSsoDependencies {
  now?: () => number
  randomBase64Url?: () => string
}

export async function beginNativeSsoRedirect(
  options: RedirectSsoOptions,
  store: NativeSsoTransactionStore,
  dependencies: NativeSsoDependencies = {},
): Promise<string> {
  const issuer = new URL(options.ssoOrigin ?? YLF_DEFAULT_SSO_ORIGIN).origin
  const random = dependencies.randomBase64Url ?? (() => randomBase64Url(32))
  const nonce = random()
  const codeVerifier = random()
  const codeChallenge = await deriveSsoPkceChallenge(codeVerifier)
  const acquisition = sanitizeAcquisitionContext(options.acquisition)
  const authorizationUrl = buildSsoRequestUrl({
    clientId: options.clientId,
    codeChallenge,
    nonce,
    redirectUri: options.redirectUri,
    scope: options.scope,
    ssoOrigin: issuer,
    ...(options.prompt ? { prompt: options.prompt } : {}),
    ...(acquisition ? { acquisition } : {}),
  })

  await store.save({
    clientId: options.clientId,
    codeVerifier,
    createdAt: (dependencies.now ?? Date.now)(),
    issuer,
    nonce,
    redirectUri: options.redirectUri,
    scope: [...options.scope],
    ...(acquisition ? { acquisition } : {}),
  })

  return authorizationUrl
}

export async function openNativeSsoRedirect(
  options: RedirectSsoOptions,
  store: NativeSsoTransactionStore,
  open: (url: string) => Promise<void>,
  dependencies: NativeSsoDependencies = {},
): Promise<void> {
  const url = await beginNativeSsoRedirect(options, store, dependencies)
  try {
    await open(url)
  }
  catch (error) {
    await store.clear()
    throw error
  }
}

export async function consumeNativeSsoCallback(
  callbackUrl: string,
  store: NativeSsoTransactionStore,
  dependencies: Pick<NativeSsoDependencies, 'now'> = {},
): Promise<SsoAuthorizationResult | Extract<SsoRedirectResult, { ok: false }> | null> {
  const stored = await store.load()
  await store.clear()

  const transaction = normalizeTransaction(stored)
  const now = (dependencies.now ?? Date.now)()
  if (!transaction
    || transaction.createdAt + TRANSACTION_TTL_MS <= now
    || !callbackMatchesRedirect(callbackUrl, transaction.redirectUri)) {
    return null
  }

  const result = parseSsoRedirectResult({
    expectedIssuer: transaction.issuer,
    expectedNonce: transaction.nonce,
    hash: new URL(callbackUrl).hash,
  })
  if (!result?.ok)
    return result

  return Object.freeze({
    ...result,
    clientId: transaction.clientId,
    codeVerifier: transaction.codeVerifier,
    redirectUri: transaction.redirectUri,
    scope: Object.freeze([...transaction.scope]),
    ...(transaction.acquisition ? { acquisition: transaction.acquisition } : {}),
  })
}

export function isNativeSsoCallbackUrl(callbackUrl: string, redirectUri: string): boolean {
  return callbackMatchesRedirect(callbackUrl, redirectUri)
}

export function stripNativeSsoCallbackHash(callbackUrl: string): string {
  try {
    const url = new URL(callbackUrl)
    return `${url.pathname}${url.search}`
  }
  catch {
    return ''
  }
}

function callbackMatchesRedirect(callbackUrl: string, redirectUri: string): boolean {
  try {
    const callback = new URL(callbackUrl)
    const callbackWithoutHash = `${callback.origin}${callback.pathname}${callback.search}`
    return callback.protocol === 'https:'
      && !callback.username
      && !callback.password
      && Boolean(callback.hash)
      && callbackWithoutHash === redirectUri
  }
  catch {
    return false
  }
}

function normalizeTransaction(value: NativeSsoTransaction | null): NativeSsoTransaction | null {
  if (!value)
    return null

  const clientId = readSsoClientId(value.clientId)
  const redirectUri = readSsoRedirectUri(value.redirectUri)
  const scope = readSsoScope(value.scope.join(' '))
  let issuer = ''
  try {
    const issuerUrl = new URL(value.issuer)
    issuer = issuerUrl.protocol === 'https:' && issuerUrl.origin === value.issuer
      ? issuerUrl.origin
      : ''
  }
  catch {
    issuer = ''
  }

  if (!clientId
    || !redirectUri
    || scope.length === 0
    || !issuer
    || !NONCE_RE.test(value.nonce)
    || !isSsoPkceVerifier(value.codeVerifier)
    || !Number.isSafeInteger(value.createdAt)) {
    return null
  }

  const acquisition = sanitizeAcquisitionContext(value.acquisition)
  return {
    clientId,
    codeVerifier: value.codeVerifier,
    createdAt: value.createdAt,
    issuer,
    nonce: value.nonce,
    redirectUri,
    scope,
    ...(acquisition ? { acquisition } : {}),
  }
}

function randomBase64Url(byteLength: number): string {
  const bytes = new Uint8Array(byteLength)
  globalThis.crypto.getRandomValues(bytes)
  let binary = ''
  for (const byte of bytes)
    binary += String.fromCharCode(byte)
  return globalThis.btoa(binary)
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(BASE64_PADDING_RE, '')
}
