import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
import { createRequire } from 'node:module'
import path from 'node:path'
// eslint-disable-next-line test/no-import-node-test -- Tests the independently deployed CloudBase authentication module.
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(__dirname, '..')
const require = createRequire(import.meta.url)

const {
  AppleIAPAuthError,
  DEFAULT_ALLOWED_PRODUCT_IDS,
  DEFAULT_BUNDLE_ID,
  PRO_LIFETIME_PRODUCT_ID,
  createAppleJWSVerifier,
  loadAppleRootCertificates,
  parseAuthorizationHeader,
  readAppleIAPConfig,
} = require(path.join(repoRoot, 'cloudfunctions', '_shared', 'apple-iap-auth.js'))

const serverCredentials = {
  COOK_APPLE_IAP_ISSUER_ID: 'b572196c-7e05-49b0-bd08-e8923ba66c3d',
  COOK_APPLE_IAP_KEY_ID: 'DEMO123456',
  COOK_APPLE_IAP_PRIVATE_KEY: '-----BEGIN PRIVATE KEY-----\nsynthetic-key\n-----END PRIVATE KEY-----',
}
const NOW = 1_760_000_000_000
const SAMPLE_JWS = ['test header', 'test payload', 'test signature']
  .map(segment => Buffer.from(segment).toString('base64url'))
  .join('.')

test('parseAuthorizationHeader supports Bearer and AppleJWS while rejecting malformed or duplicate headers', () => {
  assert.deepEqual(
    parseAuthorizationHeader({ Authorization: 'Bearer abc.def-123' }),
    { scheme: 'bearer', token: 'abc.def-123' },
  )
  assert.deepEqual(
    parseAuthorizationHeader({ authorization: `AppleJWS ${SAMPLE_JWS}` }),
    { scheme: 'apple_jws', token: SAMPLE_JWS },
  )

  for (const invalidHeaders of [
    null,
    {},
    { Authorization: '' },
    { Authorization: 'Basic dXNlcjpwYXNz' },
    { Authorization: 'AppleJWS not-a-three-part-jws' },
    { Authorization: `AppleJWS ${'a'.repeat(17_000)}.b.c` },
    { authorization: 'Bearer one', Authorization: 'Bearer two' },
  ]) {
    assert.throws(
      () => parseAuthorizationHeader(invalidHeaders),
      error => error instanceof AppleIAPAuthError && error.code === 'LOGIN_REQUIRED',
    )
  }
})

test('readAppleIAPConfig defaults to disabled, fails closed without Production appAppleId or Sandbox opt-in, and excludes buyout product ID', () => {
  assert.equal(readAppleIAPConfig({}).enabled, false)
  assert.equal(readAppleIAPConfig({ COOK_APPLE_IAP_ENABLED: 'true', COOK_APPLE_APP_ID: '6740001234' }).enabled, false)

  const prodReady = readAppleIAPConfig({
    ...serverCredentials,
    COOK_APPLE_IAP_ENABLED: 'true',
    COOK_APPLE_APP_ID: '6740001234',
  })
  assert.equal(prodReady.enabled, true)
  assert.equal(prodReady.bundleId, DEFAULT_BUNDLE_ID)
  assert.equal(prodReady.appAppleId, 6740001234)
  assert.equal(prodReady.allowSandbox, false)
  assert.deepEqual(prodReady.allowedProductIds, [...DEFAULT_ALLOWED_PRODUCT_IDS])

  const sandboxOnly = readAppleIAPConfig({
    ...serverCredentials,
    COOK_APPLE_IAP_ENABLED: 'true',
    COOK_APPLE_IAP_ALLOW_SANDBOX: 'true',
  })
  assert.equal(sandboxOnly.enabled, true)
  assert.equal(sandboxOnly.appAppleId, null)

  assert.equal(
    readAppleIAPConfig({
      ...serverCredentials,
      COOK_APPLE_IAP_ENABLED: 'true',
      COOK_APPLE_APP_ID: 'not-a-number',
      COOK_APPLE_IAP_ALLOW_SANDBOX: 'true',
    }).enabled,
    false,
  )
  assert.equal(
    readAppleIAPConfig({
      ...serverCredentials,
      COOK_APPLE_IAP_ENABLED: 'true',
      COOK_APPLE_APP_ID: '6740001234',
      COOK_APPLE_AI_PRODUCT_IDS: PRO_LIFETIME_PRODUCT_ID,
    }).enabled,
    false,
  )
})

test('loadAppleRootCertificates loads all 3 Apple Root CA certificates and SignedDataVerifier initializes', () => {
  const certs = loadAppleRootCertificates()
  assert.equal(certs.length, 3)
  assert.ok(certs.every(cert => Buffer.isBuffer(cert) && cert.length > 100))

  const { SignedDataVerifier, Environment } = require('@apple/app-store-server-library')
  const prod = new SignedDataVerifier(certs, false, Environment.PRODUCTION, DEFAULT_BUNDLE_ID, 6740001234)
  const sandbox = new SignedDataVerifier(certs, false, Environment.SANDBOX, DEFAULT_BUNDLE_ID)
  assert.ok(prod)
  assert.ok(sandbox)
})

test('createAppleJWSVerifier enforces storefront, bundleId, AI product IDs, expiry, and revocation', async () => {
  const baseConfig = readAppleIAPConfig({
    ...serverCredentials,
    COOK_APPLE_IAP_ENABLED: 'true',
    COOK_APPLE_APP_ID: '6740001234',
    COOK_APPLE_IAP_ALLOW_SANDBOX: 'true',
  })

  const makePayload = (overrides = {}) => ({
    bundleId: DEFAULT_BUNDLE_ID,
    productId: 'fun.yunle.cook.ai_month',
    originalTransactionId: '1000000999888777',
    storefront: 'USA',
    environment: 'Production',
    expiresDate: NOW + 86_400_000,
    ...overrides,
  })

  const verifyOk = createAppleJWSVerifier({
    config: baseConfig,
    now: () => NOW,
    readSubscriptionStatus: async () => SAMPLE_JWS,
    verifiers: {
      production: { verifyAndDecodeTransaction: async () => makePayload() },
      sandbox: { verifyAndDecodeTransaction: async () => { throw new Error('not sandbox') } },
    },
  })
  assert.deepEqual(await verifyOk(SAMPLE_JWS), {
    uid: 'apple:1000000999888777',
    originalTransactionId: '1000000999888777',
    productId: 'fun.yunle.cook.ai_month',
    storefront: 'USA',
    environment: 'Production',
    membership: { isActive: true, expireAt: NOW + 86_400_000 },
  })

  // Verify the same signed credential again after the subscription is revoked upstream.
  const verifyRefund = createAppleJWSVerifier({
    config: baseConfig,
    now: () => NOW,
    verifiers: { production: { verifyAndDecodeTransaction: async () => makePayload() } },
    async readSubscriptionStatus(txId, environment) {
      assert.equal(txId, '1000000999888777')
      assert.equal(environment, 'Production')
      return null
    },
  })
  assert.deepEqual((await verifyRefund(SAMPLE_JWS)).membership, { isActive: false, expireAt: null })
  const verifyUnavailable = createAppleJWSVerifier({
    config: baseConfig,
    now: () => NOW,
    verifiers: { production: { verifyAndDecodeTransaction: async () => makePayload() } },
    async readSubscriptionStatus() { throw new Error('private upstream diagnostic') },
  })
  await assert.rejects(() => verifyUnavailable(SAMPLE_JWS), { message: 'SERVICE_UNAVAILABLE' })

  // Sandbox fallback works when allowSandbox is enabled
  const verifySandbox = createAppleJWSVerifier({
    config: baseConfig,
    now: () => NOW,
    readSubscriptionStatus: async () => SAMPLE_JWS,
    verifiers: {
      production: { verifyAndDecodeTransaction: async () => { throw new Error('sandbox env mismatch') } },
      sandbox: { verifyAndDecodeTransaction: async () => makePayload({ environment: 'Sandbox', storefront: 'jpn' }) },
    },
  })
  const sandboxResult = await verifySandbox(SAMPLE_JWS)
  assert.equal(sandboxResult.storefront, 'JPN')
  assert.equal(sandboxResult.environment, 'Sandbox')
  assert.equal(sandboxResult.membership.isActive, true)

  // Mainland China ('CHN') or missing storefront fails closed with REGION_UNAVAILABLE
  for (const storefront of ['CHN', 'chn', '', undefined]) {
    const verifyRegion = createAppleJWSVerifier({
      config: baseConfig,
      now: () => NOW,
      verifiers: {
        production: { verifyAndDecodeTransaction: async () => makePayload({ storefront }) },
      },
    })
    await assert.rejects(
      () => verifyRegion(SAMPLE_JWS),
      error => error instanceof AppleIAPAuthError && error.code === 'REGION_UNAVAILABLE',
    )
  }

  // Lifetime buyout product or non-subscription lacks AI access
  for (const productId of [PRO_LIFETIME_PRODUCT_ID, 'fun.yunle.cook.unknown']) {
    const verifyProduct = createAppleJWSVerifier({
      config: baseConfig,
      now: () => NOW,
      verifiers: {
        production: { verifyAndDecodeTransaction: async () => makePayload({ productId }) },
      },
    })
    await assert.rejects(
      () => verifyProduct(SAMPLE_JWS),
      error => error instanceof AppleIAPAuthError && error.code === 'MEMBERSHIP_REQUIRED',
    )
  }

  // Wrong bundle ID or Xcode environment is rejected
  for (const overrides of [{ bundleId: 'com.evil.app' }, { environment: 'Xcode' }]) {
    const verifyBundle = createAppleJWSVerifier({
      config: baseConfig,
      now: () => NOW,
      verifiers: {
        production: { verifyAndDecodeTransaction: async () => makePayload(overrides) },
      },
    })
    await assert.rejects(
      () => verifyBundle(SAMPLE_JWS),
      error => error instanceof AppleIAPAuthError && error.code === 'LOGIN_REQUIRED',
    )
  }

  // Expired or revoked returns inactive membership
  const verifyExpired = createAppleJWSVerifier({
    config: baseConfig,
    now: () => NOW,
    verifiers: {
      production: { verifyAndDecodeTransaction: async () => makePayload({ expiresDate: NOW - 1 }) },
    },
  })
  assert.deepEqual((await verifyExpired(SAMPLE_JWS)).membership, {
    isActive: false,
    expireAt: NOW - 1,
  })

  const verifyRevoked = createAppleJWSVerifier({
    config: baseConfig,
    now: () => NOW,
    verifiers: {
      production: { verifyAndDecodeTransaction: async () => makePayload({ revocationDate: NOW - 1000 }) },
    },
  })
  assert.deepEqual((await verifyRevoked(SAMPLE_JWS)).membership, {
    isActive: false,
    expireAt: null,
  })
})
