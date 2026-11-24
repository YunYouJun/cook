const fs = require('node:fs')
const path = require('node:path')
const process = require('node:process')

const DEFAULT_BUNDLE_ID = 'fun.yunle.cook'
const DEFAULT_ALLOWED_PRODUCT_IDS = Object.freeze([
  'fun.yunle.cook.ai_month',
  'fun.yunle.cook.ai_year',
])
const PRO_LIFETIME_PRODUCT_ID = 'fun.yunle.cook.pro_lifetime'
const MAINLAND_CHINA_STOREFRONT = 'CHN'
const CERT_FILES = Object.freeze([
  'AppleIncRootCertificate.cer',
  'AppleRootCA-G2.cer',
  'AppleRootCA-G3.cer',
])
const MAX_JWS_LENGTH = 16_384
const COMPACT_JWS = /^[\w-]+\.[\w-]+\.[\w-]+$/

class AppleIAPAuthError extends Error {
  constructor(code, diagnostic) {
    super(code)
    this.code = code
    this.diagnostic = diagnostic
  }
}

function isPlainRecord(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function authorizationHeaders(headers) {
  if (!isPlainRecord(headers))
    return []
  return Object.entries(headers).filter(([name]) => name.toLowerCase() === 'authorization')
}

function parseAuthorizationHeader(headers) {
  const entries = authorizationHeaders(headers)
  const value = entries.length === 1 ? entries[0][1] : null
  if (typeof value !== 'string')
    throw new AppleIAPAuthError('LOGIN_REQUIRED', 'missing_authorization')

  const bearerMatch = /^Bearer ([\x21-\x7E]{1,8192})$/i.exec(value)
  if (bearerMatch)
    return { scheme: 'bearer', token: bearerMatch[1] }

  if (value.length <= MAX_JWS_LENGTH) {
    const prefix = 'AppleJWS '
    const token = value.slice(prefix.length)
    if (value.startsWith(prefix) && COMPACT_JWS.test(token))
      return { scheme: 'apple_jws', token }
  }

  throw new AppleIAPAuthError('LOGIN_REQUIRED', 'unsupported_authorization_scheme')
}

function parsePositiveSafeInt(raw) {
  if (typeof raw !== 'string')
    return null
  const trimmed = raw.trim()
  if (!/^[1-9]\d{0,15}$/.test(trimmed))
    return null
  const parsed = Number(trimmed)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

function parseAllowedProductIds(raw) {
  if (raw === undefined || raw === null || String(raw).trim() === '')
    return { valid: true, ids: DEFAULT_ALLOWED_PRODUCT_IDS }

  const parts = String(raw)
    .split(',')
    .map(item => item.trim())
    .filter(Boolean)
  if (parts.length === 0 || parts.length > 10)
    return { valid: false, ids: [] }

  const unique = [...new Set(parts)]
  const validFormat = unique.every(
    id => /^fun\.yunle\.cook\.[a-z0-9_.-]{1,64}$/.test(id) && id !== PRO_LIFETIME_PRODUCT_ID,
  )
  return validFormat ? { valid: true, ids: Object.freeze(unique) } : { valid: false, ids: [] }
}

function readAppleIAPConfig(env = process.env) {
  const enabled = env.COOK_APPLE_IAP_ENABLED === 'true'
  const allowSandbox = env.COOK_APPLE_IAP_ALLOW_SANDBOX === 'true'
  const bundleId = typeof env.COOK_APPLE_BUNDLE_ID === 'string' && env.COOK_APPLE_BUNDLE_ID.trim()
    ? env.COOK_APPLE_BUNDLE_ID.trim()
    : DEFAULT_BUNDLE_ID
  const appAppleIdRaw = env.COOK_APPLE_APP_ID ?? env.APPLE_APP_ID
  const appAppleId = parsePositiveSafeInt(appAppleIdRaw)
  const productResult = parseAllowedProductIds(env.COOK_APPLE_AI_PRODUCT_IDS)
  const validBundle = /^[a-z0-9-]+(?:\.[a-z0-9-]+)+$/i.test(bundleId)
  const issuerId = env.COOK_APPLE_IAP_ISSUER_ID?.trim()
  const keyId = env.COOK_APPLE_IAP_KEY_ID?.trim()
  const signingKey = env.COOK_APPLE_IAP_PRIVATE_KEY?.replaceAll('\\n', '\n').trim()
  const hasServerCredentials = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(issuerId ?? '')
    && /^[A-Z0-9]{10}$/.test(keyId ?? '')
    && signingKey?.startsWith('-----BEGIN PRIVATE KEY-----\n')
    && signingKey.endsWith('\n-----END PRIVATE KEY-----')
  const hasAppIdIfProvided = !appAppleIdRaw || !String(appAppleIdRaw).trim() || appAppleId !== null
  const canVerifyProduction = appAppleId !== null
  const configured = validBundle
    && hasAppIdIfProvided
    && productResult.valid
    && Boolean(hasServerCredentials)
    && (canVerifyProduction || allowSandbox)

  return Object.freeze({
    enabled: enabled && configured,
    configured,
    bundleId,
    appAppleId,
    allowSandbox,
    serverCredentials: hasServerCredentials ? Object.freeze({ issuerId, keyId, signingKey }) : null,
    allowedProductIds: productResult.ids,
  })
}

function loadAppleRootCertificates(certDir = path.join(__dirname, 'certs')) {
  return CERT_FILES.map((name) => {
    const filePath = path.join(certDir, name)
    return fs.readFileSync(filePath)
  })
}

function buildDefaultVerifiers(config, certDir) {
  let SignedDataVerifier
  let Environment
  try {
    ;({ SignedDataVerifier, Environment } = require('@apple/app-store-server-library'))
  }
  catch {
    throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'missing_apple_iap_library')
  }

  let rootCerts
  try {
    rootCerts = loadAppleRootCertificates(certDir)
  }
  catch {
    throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'missing_apple_root_certs')
  }

  const instances = {}
  if (config.appAppleId !== null) {
    instances.production = new SignedDataVerifier(
      rootCerts,
      false,
      Environment.PRODUCTION,
      config.bundleId,
      config.appAppleId,
    )
  }
  if (config.allowSandbox) {
    instances.sandbox = new SignedDataVerifier(
      rootCerts,
      false,
      Environment.SANDBOX,
      config.bundleId,
      config.appAppleId ?? undefined,
    )
  }
  return instances
}

function createSubscriptionStatusReader(config) {
  const clients = new Map()
  return async (txId, verifiedEnvironment) => {
    const { AppStoreServerAPIClient, Environment, Status } = require('@apple/app-store-server-library')
    const { signingKey, keyId, issuerId } = config.serverCredentials
    const environment = verifiedEnvironment === 'Production' ? Environment.PRODUCTION : Environment.SANDBOX
    if (!clients.has(environment)) {
      clients.set(environment, new AppStoreServerAPIClient(signingKey, keyId, issuerId, config.bundleId, environment))
    }
    const result = await clients.get(environment).getAllSubscriptionStatuses(txId, [Status.ACTIVE])
    if (result.bundleId !== config.bundleId || result.environment !== environment)
      throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'apple_status_response_mismatch')
    const transactions = (result.data ?? []).flatMap(group => group.lastTransactions ?? [])
    const current = transactions.find(item => item.originalTransactionId === txId && item.status === Status.ACTIVE)
    return current?.signedTransactionInfo ?? null
  }
}

function transactionAccess(payload, config, verifiedEnvironment) {
  if (payload.bundleId !== config.bundleId || payload.environment !== verifiedEnvironment)
    throw new AppleIAPAuthError('LOGIN_REQUIRED', 'apple_app_or_environment_mismatch')

  if (!config.allowedProductIds.includes(payload.productId))
    throw new AppleIAPAuthError('MEMBERSHIP_REQUIRED', 'disallowed_apple_product')

  const txId = typeof payload.originalTransactionId === 'string' ? payload.originalTransactionId : ''
  if (!/^[\w.:-]{1,128}$/.test(txId))
    throw new AppleIAPAuthError('LOGIN_REQUIRED', 'invalid_original_transaction_id')

  const storefront = typeof payload.storefront === 'string' ? payload.storefront.trim().toUpperCase() : ''
  if (!/^[A-Z]{3}$/.test(storefront) || storefront === MAINLAND_CHINA_STOREFRONT)
    throw new AppleIAPAuthError('REGION_UNAVAILABLE', 'unavailable_apple_storefront')

  if (!Number.isSafeInteger(payload.expiresDate) || payload.expiresDate <= 0)
    throw new AppleIAPAuthError('MEMBERSHIP_REQUIRED', 'missing_subscription_expiry')

  return {
    uid: `apple:${txId}`,
    originalTransactionId: txId,
    productId: payload.productId,
    storefront,
    environment: verifiedEnvironment,
    expireAt: payload.revocationDate == null ? payload.expiresDate : null,
  }
}

function createAppleJWSVerifier({
  env = process.env,
  config = readAppleIAPConfig(env),
  certDir = path.join(__dirname, 'certs'),
  verifiers,
  readSubscriptionStatus = createSubscriptionStatusReader(config),
  now = Date.now,
} = {}) {
  let cachedVerifiers = verifiers ?? null

  function getVerifiers() {
    if (!cachedVerifiers)
      cachedVerifiers = buildDefaultVerifiers(config, certDir)
    return cachedVerifiers
  }

  async function decodeVerifiedPayload(jws) {
    const activeVerifiers = getVerifiers()
    const candidates = []
    if (activeVerifiers.production)
      candidates.push({ envName: 'Production', verifier: activeVerifiers.production })
    if (activeVerifiers.sandbox && config.allowSandbox)
      candidates.push({ envName: 'Sandbox', verifier: activeVerifiers.sandbox })

    if (candidates.length === 0)
      throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'no_apple_verifier_configured')

    for (const candidate of candidates) {
      try {
        const payload = await candidate.verifier.verifyAndDecodeTransaction(jws)
        if (payload && typeof payload === 'object')
          return { payload, verifiedEnvironment: candidate.envName }
      }
      catch {
        // Try next allowed Apple environment (e.g., Sandbox for App Review / TestFlight).
      }
    }
    throw new AppleIAPAuthError('LOGIN_REQUIRED', 'invalid_apple_jws_signature')
  }

  return async function verifyAppleJWS(jws) {
    if (!config.enabled)
      throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'apple_iap_disabled')
    if (typeof jws !== 'string' || jws.length > MAX_JWS_LENGTH || !COMPACT_JWS.test(jws))
      throw new AppleIAPAuthError('LOGIN_REQUIRED', 'malformed_apple_jws')

    const { payload, verifiedEnvironment } = await decodeVerifiedPayload(jws)
    const original = transactionAccess(payload, config, verifiedEnvironment)
    let access = original
    if (original.expireAt > now()) {
      // A valid client JWS can predate a refund. Confirm the current status before admitting it.
      let currentJWS
      try {
        currentJWS = await readSubscriptionStatus(original.originalTransactionId, verifiedEnvironment)
      }
      catch {
        throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'apple_subscription_status_unavailable')
      }
      if (currentJWS) {
        const verifier = verifiedEnvironment === 'Production' ? getVerifiers().production : getVerifiers().sandbox
        let currentPayload
        try {
          currentPayload = await verifier.verifyAndDecodeTransaction(currentJWS)
        }
        catch {
          throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'invalid_apple_subscription_status')
        }
        access = transactionAccess(currentPayload, config, verifiedEnvironment)
        if (access.originalTransactionId !== original.originalTransactionId)
          throw new AppleIAPAuthError('SERVICE_UNAVAILABLE', 'apple_subscription_identity_mismatch')
        // The client must present its own new transaction before extending access beyond the original expiry.
        access.expireAt = access.expireAt === null ? null : Math.min(access.expireAt, original.expireAt)
      }
      else {
        access = { ...original, expireAt: null }
      }
    }

    const { expireAt, ...identity } = access
    return { ...identity, membership: { isActive: expireAt > now(), expireAt } }
  }
}

module.exports = {
  AppleIAPAuthError,
  CERT_FILES,
  DEFAULT_ALLOWED_PRODUCT_IDS,
  DEFAULT_BUNDLE_ID,
  MAINLAND_CHINA_STOREFRONT,
  PRO_LIFETIME_PRODUCT_ID,
  authorizationHeaders,
  createAppleJWSVerifier,
  loadAppleRootCertificates,
  parseAuthorizationHeader,
  readAppleIAPConfig,
}
