import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
// eslint-disable-next-line test/no-import-node-test -- The independently deployed function is tested without the Nuxt or Vitest runtime.
import test from 'node:test'

const require = createRequire(import.meta.url)
const guidanceModule = require('../cloudfunctions/cook-ai-guidance/index.js')
const { QuotaError } = require('../cloudfunctions/cook-ai-guidance/quota.js')
const intentModule = require('../cloudfunctions/cook-ai-intent/index.js')

const {
  ACCOUNT_URL,
  IDENTITY_URL,
  MODEL_URL,
  GuidanceServiceError,
  createAdapters,
  guidancePrompt,
  readConfig,
  resolveContext,
  validSnapshot,
  validateDraft,
} = guidanceModule._internal

const NOW = Date.parse('2026-11-09T01:00:00Z')
const USER_TOKEN = 'fixture-user-token'
const PROVIDER_KEY = 'fixture-provider-secret'
const ENV = {
  COOK_AI_GUIDANCE_ENABLED: 'true',
  COOK_AI_DEEPSEEK_API_KEY: PROVIDER_KEY,
  COOK_AI_QUOTA_SALT: 'fixture-quota-salt-at-least-32-characters',
  COOK_AI_USER_DAILY_LIMIT: '20',
  COOK_AI_USER_MINUTE_LIMIT: '3',
  COOK_AI_DAILY_REQUEST_BUDGET: '100',
}
const CONFIG = readConfig(ENV)
const SNAPSHOT = {
  schemaVersion: 1,
  policyVersion: 1,
  dataVersion: 'fixture-v1',
  recipes: {
    250: {
      sourceFingerprint: '12257c0baaf2700b',
      name: '番茄炒蛋',
      ingredients: ['番茄', '鸡蛋', '食用油'],
      steps: [
        { id: '1|热锅', text: '热锅，加入 4ml 食用油' },
        { id: '2|炒蛋', text: '倒入鸡蛋翻炒 1 分钟' },
      ],
    },
  },
}

function input(change = {}) {
  return {
    action: 'guidance',
    schemaVersion: 1,
    policyVersion: 1,
    dataVersion: SNAPSHOT.dataVersion,
    sourceFingerprint: SNAPSHOT.recipes[250].sourceFingerprint,
    recipeID: 250,
    stepID: '1|热锅',
    servings: 2,
    kind: 'explain',
    requestID: 'request-fixture',
    contextRevision: 'context-fixture',
    ...change,
  }
}

function event(body = input(), headers = { Authorization: `Bearer ${USER_TOKEN}` }) {
  return { httpMethod: 'POST', headers, body: JSON.stringify(body) }
}

function draft(change = {}) {
  return {
    status: 'answer',
    answer: '按原文先把锅烧热，再加入 4ml 食用油。',
    sourceIDs: ['step:1|热锅', 'recipe:name'],
    ...change,
  }
}

function envelope(value = draft(), overrides = {}) {
  return { choices: [{ finish_reason: 'stop', message: { role: 'assistant', content: JSON.stringify(value) }, ...overrides }] }
}

function fixture(overrides = {}) {
  const calls = []
  const options = {
    config: CONFIG,
    snapshot: SNAPSHOT,
    now: () => NOW,
    async verifyIdentity(token) {
      calls.push(['identity', token])
      return { uid: 'verified-user' }
    },
    async readMembership(token) {
      calls.push(['membership', token])
      return { isActive: true, expireAt: NOW + 60_000 }
    },
    async reserveQuota(value) { calls.push(['quota', value]) },
    async complete(context) {
      calls.push(['model', context])
      return draft()
    },
    ...overrides,
  }
  return { calls, handler: guidanceModule.createHandler(options) }
}

async function assertCode(handler, request, status, code) {
  const result = await handler(request)
  assert.equal(result.statusCode, status)
  assert.deepEqual(JSON.parse(result.body), { ok: false, code })
  assert.equal(result.headers['Cache-Control'], 'no-store')
  return result
}

function jsonResponse(body, { status = 200, url = '', redirected = false } = {}) {
  const result = new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
  Object.defineProperties(result, { url: { value: url }, redirected: { value: redirected } })
  return result
}

function networkAdapter(overrides = {}, config = CONFIG) {
  const requests = []
  const fetchImpl = async (url, options) => {
    requests.push({ url, options })
    assert.equal(options.redirect, 'error')
    assert.equal(options.credentials, 'omit')
    assert.equal(options.cache, 'no-store')
    assert.ok(options.signal instanceof AbortSignal)
    if (overrides[url])
      return overrides[url](options)
    if (url === IDENTITY_URL)
      return jsonResponse({ sub: 'verified-user', status: 'ACTIVE', is_anonymous: false }, { url })
    if (url === ACCOUNT_URL)
      return jsonResponse({ membership: { isActive: true, expireAt: NOW + 60_000 } }, { url })
    if (url === MODEL_URL)
      return jsonResponse(envelope(), { url })
    throw new Error('Unexpected endpoint')
  }
  return { requests, ...createAdapters({ fetchImpl, config }) }
}

test('the guidance gate is independent from search intent and requires every bounded deployment value', () => {
  assert.equal(CONFIG.enabled, true)
  assert.equal(CONFIG.provider, 'deepseek')
  assert.equal(CONFIG.model, 'deepseek-flash')
  assert.equal(readConfig({ ...ENV, COOK_AI_ENABLED: 'true' }).enabled, true)
  assert.equal(readConfig({ ...ENV, COOK_AI_ENABLED: 'true' }).enabled, intentModule._internal.readConfig({ ...ENV, COOK_AI_ENABLED: 'true' }).enabled)

  const withoutGuidanceGate = { ...ENV }
  delete withoutGuidanceGate.COOK_AI_GUIDANCE_ENABLED
  assert.equal(readConfig({ ...withoutGuidanceGate, COOK_AI_ENABLED: 'true' }).enabled, false)
  assert.equal(intentModule._internal.readConfig({ ...withoutGuidanceGate, COOK_AI_ENABLED: 'true' }).enabled, true)

  const withoutIntentGate = { ...ENV }
  delete withoutIntentGate.COOK_AI_GUIDANCE_ENABLED
  assert.equal(intentModule._internal.readConfig(withoutIntentGate).enabled, false)
  assert.equal(readConfig(ENV).enabled, true)
  for (const key of Object.keys(ENV)) {
    const changed = { ...ENV }
    delete changed[key]
    assert.equal(readConfig(changed).enabled, false, key)
  }
  for (const [key, value] of [
    ['COOK_AI_GUIDANCE_ENABLED', 'TRUE'],
    ['COOK_AI_GUIDANCE_ENABLED', 'false'],
    ['COOK_AI_USER_DAILY_LIMIT', '1001'],
    ['COOK_AI_USER_MINUTE_LIMIT', '61'],
    ['COOK_AI_DAILY_REQUEST_BUDGET', '10001'],
    ['COOK_AI_USER_DAILY_LIMIT', '0'],
    ['COOK_AI_USER_MINUTE_LIMIT', '1.5'],
    ['COOK_AI_DAILY_REQUEST_BUDGET', '2x'],
    ['COOK_AI_QUOTA_SALT', 'too-short'],
    ['COOK_AI_DEEPSEEK_API_KEY', 'unsafe\nkey'],
  ]) {
    assert.equal(readConfig({ ...ENV, [key]: value }).enabled, false, `${key}=${value}`)
  }
  const cloudbase = { ...ENV, COOK_AI_PROVIDER: 'cloudbase' }
  delete cloudbase.COOK_AI_DEEPSEEK_API_KEY
  assert.equal(readConfig(cloudbase).enabled, true)
})

test('capabilities authenticate and report guidance readiness without consuming quota', async () => {
  for (const [membership, enabled, expected] of [
    [{ isActive: true, expireAt: NOW + 1 }, true, true],
    [{ isActive: false, expireAt: null }, true, false],
    [{ isActive: true, expireAt: NOW }, true, false],
    [{ isActive: true, expireAt: NOW + 1 }, false, false],
  ]) {
    const f = fixture({ config: { ...CONFIG, enabled }, readMembership: async () => membership })
    const result = await f.handler(event({ action: 'capabilities' }))
    assert.equal(result.statusCode, 200)
    assert.deepEqual(JSON.parse(result.body), {
      ok: true,
      serviceVersion: 1,
      enabled: expected,
      membership: { isActive: membership.isActive && membership.expireAt > NOW, expireAt: membership.expireAt },
    })
    assert.deepEqual(f.calls.map(call => call[0]), ['identity'])
  }
})

test('guidance derives identity, verifies membership, reserves once and returns bound trusted sources', async () => {
  const f = fixture()
  const result = await f.handler({
    ...event(input({ kind: 'question', question: '怎样判断油温？' })),
    uid: 'attacker',
    isMember: true,
    recipeText: 'untrusted replacement',
  })
  assert.equal(result.statusCode, 200)
  assert.deepEqual(JSON.parse(result.body), {
    ok: true,
    guidance: {
      schemaVersion: 1,
      policyVersion: 1,
      dataVersion: 'fixture-v1',
      sourceFingerprint: '12257c0baaf2700b',
      recipeID: 250,
      stepID: '1|热锅',
      requestID: 'request-fixture',
      contextRevision: 'context-fixture',
      status: 'answer',
      answer: '按原文先把锅烧热，再加入 4ml 食用油。',
      sourceIDs: ['step:1|热锅', 'recipe:name'],
    },
  })
  assert.deepEqual(f.calls.map(call => call[0]), ['identity', 'membership', 'quota', 'model'])
  assert.deepEqual(f.calls[2][1], { uid: 'verified-user', now: NOW })
  const context = f.calls[3][1]
  assert.equal(context.currentStep, '热锅，加入 4ml 食用油')
  assert.equal(context.request.question, '怎样判断油温？')
  assert.ok(!result.body.includes('attacker'))
  assert.ok(!result.body.includes('untrusted replacement'))
})

test('source and request drift fail before quota or model calls', async () => {
  for (const [change, status, code, expectedCalls] of [
    [{ schemaVersion: 2 }, 400, 'INVALID_REQUEST', []],
    [{ dataVersion: 'fixture-v0' }, 409, 'VERSION_MISMATCH', ['identity', 'membership']],
    [{ recipeID: 999 }, 409, 'SOURCE_NOT_FOUND', ['identity', 'membership']],
    [{ sourceFingerprint: '0000000000000000' }, 409, 'SOURCE_MISMATCH', ['identity', 'membership']],
    [{ stepID: '2|missing' }, 409, 'SOURCE_MISMATCH', ['identity', 'membership']],
    [{ stepID: '1|热锅', recipeText: 'replacement' }, 400, 'INVALID_REQUEST', []],
    [{ kind: 'explain', question: 'must not be sent' }, 400, 'INVALID_REQUEST', []],
    [{ servings: 21 }, 400, 'INVALID_REQUEST', []],
  ]) {
    const f = fixture()
    await assertCode(f.handler, event(input(change)), status, code)
    assert.deepEqual(f.calls.map(call => call[0]), expectedCalls)
  }
})

test('model output must cite known sources including the current step and may not add numbers', async () => {
  for (const [value, diagnostic] of [
    [draft({ sourceIDs: ['step:2|炒蛋'] }), 'guidance_missing_current_source'],
    [draft({ sourceIDs: ['step:1|热锅', 'step:unknown'] }), 'guidance_unknown_source'],
    [draft({ answer: '再加入 5ml 食用油。' }), 'guidance_unsafe_numbers'],
    [draft({ answer: '先烧热锅。', extra: true }), 'guidance_root_contract'],
    [draft({ sourceIDs: [] }), 'guidance_draft_contract'],
  ]) {
    const failures = []
    const f = fixture({ complete: async () => value, logFailure: (...args) => failures.push(args) })
    await assertCode(f.handler, event(), 502, 'INVALID_MODEL_RESPONSE')
    assert.deepEqual(failures, [['INVALID_MODEL_RESPONSE', diagnostic]])
  }
})

test('membership and quota failures stay fixed and never call the model', async () => {
  const inactive = fixture({ readMembership: async () => ({ isActive: false, expireAt: null }) })
  await assertCode(inactive.handler, event(), 403, 'MEMBERSHIP_REQUIRED')
  assert.deepEqual(inactive.calls.map(call => call[0]), ['identity'])

  const rateLimited = fixture({
    reserveQuota: async () => {
      throw new QuotaError('RATE_LIMITED')
    },
  })
  await assertCode(rateLimited.handler, event(), 429, 'RATE_LIMITED')
  assert.deepEqual(rateLimited.calls.map(call => call[0]), ['identity', 'membership'])

  let clockChecks = 0
  const expiresDuringAdmission = fixture({
    now: () => clockChecks++ === 0 ? NOW : NOW + 60_001,
  })
  await assertCode(expiresDuringAdmission.handler, event(), 403, 'MEMBERSHIP_REQUIRED')
  assert.deepEqual(expiresDuringAdmission.calls.map(call => call[0]), ['identity', 'membership', 'quota'])
})

test('the provider prompt is built only from the trusted snapshot and explicit question', async () => {
  const context = resolveContext(SNAPSHOT, input({ kind: 'question', question: '怎样判断油温？' }))
  assert.equal(validSnapshot(SNAPSHOT), true)
  const prompt = guidancePrompt(context)
  assert.ok(prompt.includes('step:1|热锅'))
  assert.ok(prompt.includes('怎样判断油温？') === false, 'question stays in the user message')
  assert.ok(!prompt.includes('untrusted replacement'))
  assert.deepEqual(validateDraft(draft(), context), draft())

  const adapter = networkAdapter()
  const result = await adapter.complete(context)
  assert.deepEqual(result, draft())
  const model = adapter.requests.find(request => request.url === MODEL_URL)
  const body = JSON.parse(model.options.body)
  assert.equal(body.max_tokens, 1400)
  assert.deepEqual(body.thinking, { type: 'disabled' })
  assert.equal(body.messages.length, 2)
  assert.ok(body.messages[0].content.includes('SOURCE DATA:'))
  assert.ok(body.messages[0].content.includes('step:1|热锅'))
  assert.equal(body.messages[1].content, '怎样判断油温？')
  assert.ok(!body.messages[0].content.includes('untrusted replacement'))
})

test('the source snapshot rejects duplicate identities and malformed entries', async () => {
  assert.equal(validSnapshot(null), false)
  assert.equal(validSnapshot({ ...SNAPSHOT, dataVersion: '' }), false)
  assert.equal(validSnapshot({
    ...SNAPSHOT,
    recipes: { 250: { ...SNAPSHOT.recipes[250], steps: [...SNAPSHOT.recipes[250].steps, SNAPSHOT.recipes[250].steps[0]] } },
  }), false)
  assert.equal(validSnapshot({
    ...SNAPSHOT,
    recipes: { 250: { ...SNAPSHOT.recipes[250], ingredients: ['鸡蛋', '鸡蛋'] } },
  }), false)
  const source = await readFile(new URL('../cloudfunctions/cook-ai-guidance/sources.json', import.meta.url), 'utf8')
  assert.ok(!source.includes('untrusted replacement'))
})

test('deployed modules keep separate contracts and diagnostic classes', () => {
  assert.equal(ACCOUNT_URL, intentModule._internal.ACCOUNT_URL)
  assert.equal(IDENTITY_URL, intentModule._internal.IDENTITY_URL)
  assert.equal(MODEL_URL, intentModule._internal.MODEL_URL)
  assert.notEqual(GuidanceServiceError, intentModule._internal.IntentServiceError)
  assert.ok(require('../cloudfunctions/cook-ai-guidance/package.json').dependencies['@cloudbase/node-sdk'])
})
