import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
// eslint-disable-next-line test/no-import-node-test -- The independently deployed function is tested without the Nuxt or Vitest runtime.
import test from 'node:test'
import { runInNewContext } from 'node:vm'

const require = createRequire(import.meta.url)
const { createHandler, _internal } = require('../cloudfunctions/cook-ai-intent/index.js')
const { COLLECTION, QuotaError, cleanupExpiredQuota, createQuotaReservation } = require('../cloudfunctions/cook-ai-intent/quota.js')

const { IntentServiceError, ACCOUNT_URL, IDENTITY_URL, MODEL_URL, createAdapters, readConfig, validateIntent } = _internal

const NOW = Date.parse('2026-10-29T01:00:00Z')
const USER_TOKEN = 'fixture-user-token'
const PROVIDER_KEY = 'fixture-provider-secret'
const ENV = {
  COOK_AI_ENABLED: 'true',
  COOK_AI_DEEPSEEK_API_KEY: PROVIDER_KEY,
  COOK_AI_QUOTA_SALT: 'fixture-quota-salt-at-least-32-characters',
  COOK_AI_USER_DAILY_LIMIT: '20',
  COOK_AI_USER_MINUTE_LIMIT: '3',
  COOK_AI_DAILY_REQUEST_BUDGET: '100',
}
const CONFIG = readConfig(ENV)
const CONTRACT = {
  schemaVersion: 1,
  policyVersion: 1,
  dataVersion: 'fixture-v1',
  stuff: ['鸡蛋', '西葫芦', '番茄', '洋葱'],
  tools: ['电饭煲', '微波炉'],
  difficulty: ['简单', '普通', '困难'],
  methodGroups: ['炒菜', '蒸'],
  availableGoals: ['less-added-sugar', 'balanced-pairing'],
}

function intent(change = {}) {
  return {
    schemaVersion: 1,
    policyVersion: 1,
    dataVersion: CONTRACT.dataVersion,
    source: 'deepseek',
    query: { stuff: ['鸡蛋'], unknown: [], avoid: [], noSpicy: true, difficulty: [], methodGroups: [] },
    unresolved: [],
    ...change,
  }
}

function input(change = {}) {
  return { action: 'parse', text: '鸡蛋，不吃辣', schemaVersion: 1, policyVersion: 1, dataVersion: CONTRACT.dataVersion, ...change }
}

function event(body = input(), headers = { Authorization: `Bearer ${USER_TOKEN}` }) {
  return { httpMethod: 'POST', headers, body: JSON.stringify(body) }
}

function fixture(overrides = {}) {
  const calls = []
  const options = {
    config: CONFIG,
    contract: CONTRACT,
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
    async complete(text) {
      calls.push(['model', text])
      return intent()
    },
    ...overrides,
  }
  return { calls, handler: createHandler(options) }
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

function envelope(value = intent(), overrides = {}) {
  return { choices: [{ finish_reason: 'stop', message: { role: 'assistant', content: JSON.stringify(value) }, ...overrides }] }
}

function networkFixture(overrides = {}, { config = CONFIG, getModel } = {}) {
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
      return jsonResponse({ coin: 7, membership: { isActive: true, expireAt: NOW + 60_000, level: 'basic' } }, { url })
    if (url === MODEL_URL)
      return jsonResponse(envelope(), { url })
    throw new Error('Unexpected endpoint')
  }
  return { requests, config, ...createAdapters({ fetchImpl, getModel, config, contract: CONTRACT }) }
}

function cloudbaseFixture({ generateText = async () => ({ rawResponses: [envelope()] }), config = readConfig({ ...ENV, COOK_AI_PROVIDER: 'cloudbase', COOK_AI_DEEPSEEK_API_KEY: '' }) } = {}) {
  const modelCalls = []
  const getModel = () => {
    modelCalls.push(['init'])
    return {
      async generateText(request) {
        modelCalls.push(['generate', request])
        return generateText(request)
      },
    }
  }
  return { modelCalls, ...networkFixture({}, { config, getModel }) }
}

class FakeDatabase {
  rows = new Map()
  attempts = 0
  failures = []
  missingCollection = false
  #tail = Promise.resolve()

  async runTransaction(work) {
    const run = this.#tail.then(async () => {
      this.attempts++
      if (this.failures.length)
        throw this.failures.shift()
      const draft = new Map([...this.rows].map(([key, value]) => [key, structuredClone(value)]))
      const result = await work({
        collection: (name) => {
          assert.equal(name, COLLECTION)
          if (this.missingCollection)
            throw new Error('collection not found')
          return { doc: id => ({
            get: async () => ({ data: draft.has(id) ? [draft.get(id)] : [] }),
            set: async (value) => { draft.set(id, structuredClone(value)) },
          }) }
        },
      })
      this.rows = draft
      return result
    })
    this.#tail = run.catch(() => undefined)
    return run
  }
}

test('configuration requires an explicit gate and every bounded deployment value', () => {
  assert.equal(CONFIG.enabled, true)
  assert.equal(CONFIG.provider, 'deepseek')
  assert.equal(CONFIG.model, 'deepseek-flash')
  for (const key of Object.keys(ENV)) {
    const changed = { ...ENV }
    delete changed[key]
    assert.equal(readConfig(changed).enabled, false, key)
  }
  for (const [key, value] of [
    ['COOK_AI_ENABLED', 'TRUE'],
    ['COOK_AI_ENABLED', 'false'],
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
})

test('provider selection validates only the active model and requires a key only for direct DeepSeek', () => {
  const cloudbaseEnv = { ...ENV, COOK_AI_PROVIDER: 'cloudbase' }
  delete cloudbaseEnv.COOK_AI_DEEPSEEK_API_KEY
  assert.equal(readConfig(cloudbaseEnv).enabled, true)
  assert.equal(readConfig(cloudbaseEnv).model, 'deepseek-v4.1-flash')
  assert.equal(readConfig({ ...cloudbaseEnv, COOK_AI_DEEPSEEK_API_KEY: 'unsafe\nkey', COOK_AI_DEEPSEEK_MODEL: 'invalid' }).enabled, true)
  assert.equal(readConfig({ ...ENV, COOK_AI_CLOUDBASE_MODEL: 'invalid' }).enabled, true)
  assert.equal(readConfig({ ...ENV, COOK_AI_DEEPSEEK_MODEL: 'deepseek-reasoner' }).model, 'deepseek-reasoner')
  for (const provider of ['', 'unknown', 'CloudBase', 'custom-deepseek-open'])
    assert.equal(readConfig({ ...ENV, COOK_AI_PROVIDER: provider }).enabled, false)
  for (const key of Object.keys(cloudbaseEnv)) {
    if (key === 'COOK_AI_PROVIDER')
      continue
    const changed = { ...cloudbaseEnv }
    delete changed[key]
    assert.equal(readConfig(changed).enabled, false, key)
  }
  for (const provider of ['deepseek', 'cloudbase']) {
    const modelVariable = provider === 'cloudbase' ? 'COOK_AI_CLOUDBASE_MODEL' : 'COOK_AI_DEEPSEEK_MODEL'
    for (const model of ['', 'hy3', 'deepseek-flash\n', 'deepseek flash', 'https://evil.example', 'deepseek-', `deepseek-${'a'.repeat(101)}`])
      assert.equal(readConfig({ ...ENV, COOK_AI_PROVIDER: provider, [modelVariable]: model }).enabled, false, model)
  }
  assert.equal(readConfig({ ...cloudbaseEnv, COOK_AI_PROVIDER: 'deepseek' }).enabled, false)
})

test('platform deployment defaults override legacy Cook routing without changing business limits', () => {
  for (const provider of ['deepseek', 'cloudbase']) {
    const config = readConfig({
      ...ENV,
      COOK_AI_PROVIDER: provider === 'deepseek' ? 'cloudbase' : 'deepseek',
      COOK_AI_DEEPSEEK_MODEL: 'deepseek-chat',
      COOK_AI_CLOUDBASE_MODEL: 'deepseek-v4.1-flash',
      YUNLEFUN_AI_DEFAULT_PROVIDER: provider,
      YUNLEFUN_AI_DEFAULT_DEEPSEEK_MODEL: 'deepseek-flash',
      YUNLEFUN_AI_DEFAULT_CLOUDBASE_MODEL: 'deepseek-v4.1-flash-1017',
    })
    assert.equal(config.provider, provider)
    assert.equal(config.model, provider === 'deepseek' ? 'deepseek-flash' : 'deepseek-v4.1-flash-1017')
    assert.equal(config.enabled, true)
    for (const key of ['apiKey', 'quotaSalt', 'userDailyLimit', 'userMinuteLimit', 'dailyRequestBudget'])
      assert.equal(config[key], CONFIG[key])
  }
  const cloudbase = { ...ENV, YUNLEFUN_AI_DEFAULT_PROVIDER: 'cloudbase', COOK_AI_DEEPSEEK_API_KEY: '' }
  assert.equal(readConfig(cloudbase).enabled, true)
  assert.equal(readConfig({ ...cloudbase, YUNLEFUN_AI_DEFAULT_PROVIDER: 'deepseek' }).enabled, false)
  assert.equal(readConfig({ ...cloudbase, COOK_AI_ENABLED: 'false' }).enabled, false)
  for (const key of ['COOK_AI_QUOTA_SALT', 'COOK_AI_USER_DAILY_LIMIT', 'COOK_AI_USER_MINUTE_LIMIT', 'COOK_AI_DAILY_REQUEST_BUDGET'])
    assert.equal(readConfig({ ...cloudbase, [key]: '' }).enabled, false, key)
})

test('invalid platform routing fails closed rather than falling back to valid legacy values', () => {
  for (const provider of ['', 'unknown', 'CloudBase', 'custom-deepseek-open'])
    assert.equal(readConfig({ ...ENV, YUNLEFUN_AI_DEFAULT_PROVIDER: provider }).enabled, false)
  for (const provider of ['deepseek', 'cloudbase']) {
    const selected = provider === 'deepseek' ? 'YUNLEFUN_AI_DEFAULT_DEEPSEEK_MODEL' : 'YUNLEFUN_AI_DEFAULT_CLOUDBASE_MODEL'
    const other = provider === 'deepseek' ? 'YUNLEFUN_AI_DEFAULT_CLOUDBASE_MODEL' : 'YUNLEFUN_AI_DEFAULT_DEEPSEEK_MODEL'
    for (const model of ['', 'hy3', 'deepseek-flash\n', 'deepseek flash', 'https://evil.example'])
      assert.equal(readConfig({ ...ENV, YUNLEFUN_AI_DEFAULT_PROVIDER: provider, [selected]: model }).enabled, false)
    assert.equal(readConfig({ ...ENV, YUNLEFUN_AI_DEFAULT_PROVIDER: provider, [other]: 'unused-invalid-model' }).enabled, true)
  }
})

test('deployed entry point keeps its startup routing snapshot and accesses only quota collections', async () => {
  const source = await readFile(new URL('../cloudfunctions/cook-ai-intent/index.js', import.meta.url), 'utf8')
  const environment = { ...ENV, YUNLEFUN_AI_DEFAULT_PROVIDER: 'deepseek' }
  const database = new FakeDatabase()
  const collections = []
  database.command = { lt: value => ({ lt: value }) }
  database.collection = (name) => {
    collections.push(name)
    assert.equal(name, COLLECTION)
    return { where: () => ({ limit: () => ({ get: async () => ({ data: [] }) }) }) }
  }
  const models = []
  const httpModels = []
  let initializations = 0
  function boot() {
    const sandbox = {
      exports: {},
      AbortSignal,
      Date: class extends Date {
        static now() { return NOW }
      },
      async fetch(url, options) {
        if (url === IDENTITY_URL)
          return jsonResponse({ sub: 'verified-user', status: 'ACTIVE' })
        if (url === ACCOUNT_URL)
          return jsonResponse({ membership: { isActive: true, expireAt: NOW + 60_000 } })
        assert.equal(url, MODEL_URL)
        httpModels.push(JSON.parse(options.body).model)
        return jsonResponse(envelope())
      },
      require(name) {
        if (name === 'node:process')
          return { env: environment }
        if (name === './contract.json')
          return CONTRACT
        if (name === '@cloudbase/node-sdk') {
          return { SYMBOL_CURRENT_ENV: 'fixture-environment', init: () => {
            initializations++
            return {
              database: () => database,
              ai: () => ({ createModel: (group) => {
                assert.equal(group, 'cloudbase')
                return { generateText: async (request) => {
                  models.push(request.model)
                  return { rawResponses: [envelope()] }
                } }
              } }),
            }
          } }
        }
        if (name === './quota')
          return require('../cloudfunctions/cook-ai-intent/quota.js')
        return require(name)
      },
    }
    runInNewContext(source, sandbox)
    return sandbox.exports.main
  }
  const deployed = boot()
  assert.equal((await deployed(event({ action: 'capabilities' }))).statusCode, 200)
  assert.equal(initializations, 0)
  environment.YUNLEFUN_AI_DEFAULT_PROVIDER = 'cloudbase'
  environment.YUNLEFUN_AI_DEFAULT_CLOUDBASE_MODEL = 'deepseek-v4.1-flash-1017'
  assert.equal((await deployed(event())).statusCode, 200)
  assert.deepEqual(httpModels, ['deepseek-flash'])
  assert.deepEqual(models, [])
  assert.equal((await boot()(event())).statusCode, 200)
  assert.deepEqual(models, ['deepseek-v4.1-flash-1017'])
  assert.deepEqual(collections, [COLLECTION, COLLECTION])
  assert.equal(database.attempts, 2)
})

test('capabilities authenticates and reports only service and membership availability', async () => {
  for (const [membership, configured, enabled] of [
    [{ isActive: true, expireAt: NOW + 1 }, true, true],
    [{ isActive: false, expireAt: null }, true, false],
    [{ isActive: true, expireAt: NOW }, true, false],
    [{ isActive: true, expireAt: NOW + 1 }, false, false],
  ]) {
    const f = fixture({ config: { ...CONFIG, enabled: configured }, readMembership: async () => membership })
    const result = await f.handler(event({ action: 'capabilities' }))
    assert.equal(result.statusCode, 200)
    assert.deepEqual(JSON.parse(result.body), {
      ok: true,
      serviceVersion: 1,
      enabled,
      membership: { isActive: membership.isActive && membership.expireAt > NOW, expireAt: membership.expireAt },
    })
    assert.deepEqual(f.calls.map(call => call[0]), ['identity'])
  }
})

test('a parse derives identity, verifies membership, reserves once, then validates a condition draft', async () => {
  const f = fixture()
  const result = await f.handler({ ...event(), uid: 'attacker', isMember: true, accessToken: 'spoof' })
  assert.equal(result.statusCode, 200)
  assert.deepEqual(JSON.parse(result.body), { ok: true, intent: intent() })
  assert.deepEqual(f.calls, [
    ['identity', USER_TOKEN],
    ['membership', USER_TOKEN],
    ['quota', { uid: 'verified-user', now: NOW }],
    ['model', '鸡蛋，不吃辣'],
  ])
  assert.ok(!result.body.includes('attacker'))
})

test('unsupported methods, malformed and oversized bodies, spoof fields and missing fields do not reach identity or model', async () => {
  const requests = [
    { ...event(), httpMethod: 'GET' },
    { action: 'parse', uid: 'spoof' },
    { ...event(), body: '{' },
    { ...event(), body: 'null' },
    { ...event(), body: '[]' },
    { ...event(), isBase64Encoded: true },
    { ...event(), body: ' '.repeat(8193) },
    event({ action: 'capabilities', uid: 'spoof' }),
    event({ action: 'capabilities', isMember: true }),
    event(input({ uid: 'spoof' })),
    event(input({ isMember: true })),
    event(input({ provider: 'cloudbase' })),
    event(input({ model: 'deepseek-v4.1-flash' })),
    event(input({ stuff: [] })),
    event(input({ text: '' })),
    event(input({ text: ' \n' })),
    event(input({ text: '字'.repeat(1001) })),
    event(input({ schemaVersion: '1' })),
    event(input({ policyVersion: true })),
    event(input({ action: 'other' })),
  ]
  for (const request of requests) {
    const f = fixture()
    const result = await f.handler(request)
    assert.ok([400, 405].includes(result.statusCode))
    assert.deepEqual(f.calls, [])
  }
})

test('only one case-insensitive Bearer header is accepted', async () => {
  for (const headers of [{}, { Authorization: '' }, { Authorization: 'Basic token' }, { Authorization: 'Bearer a b' }, { Authorization: ['Bearer token'] }, { Authorization: 'Bearer first', authorization: 'Bearer second' }]) {
    const f = fixture()
    await assertCode(f.handler, event(input(), headers), 401, 'LOGIN_REQUIRED')
    assert.deepEqual(f.calls, [])
  }
  const f = fixture()
  assert.equal((await f.handler(event(input(), { aUtHoRiZaTiOn: `bearer ${USER_TOKEN}` }))).statusCode, 200)
})

test('nonmembers, expired and malformed membership cannot reserve or reach provider', async () => {
  for (const membership of [{ isActive: false, expireAt: null }, { isActive: true, expireAt: NOW }, { isActive: true, expireAt: NOW - 1 }, { isActive: true, expireAt: String(NOW + 100) }, null]) {
    const f = fixture({ readMembership: async () => membership })
    await assertCode(f.handler, event(), 403, 'MEMBERSHIP_REQUIRED')
    assert.deepEqual(f.calls.map(call => call[0]), ['identity'])
  }
})

test('membership that expires during quota admission cannot reach the provider or refund its reservation', async () => {
  const times = [NOW, NOW + 60_000]
  const f = fixture({ now: () => times.shift() })
  await assertCode(f.handler, event(), 403, 'MEMBERSHIP_REQUIRED')
  assert.deepEqual(f.calls.map(call => call[0]), ['identity', 'membership', 'quota'])
})

test('unconfigured service and missing or obsolete trusted contract fail closed before quota and model', async () => {
  for (const changes of [{ config: readConfig({}) }, { contract: null }, { contract: { ...CONTRACT, availableGoals: ['keto'] } }]) {
    const f = fixture(changes)
    await assertCode(f.handler, event(), 503, 'SERVICE_UNAVAILABLE')
    assert.deepEqual(f.calls.map(call => call[0]), ['identity', 'membership'])
  }
  for (const changes of [{ schemaVersion: 2 }, { policyVersion: 2 }, { dataVersion: 'stale' }]) {
    const f = fixture()
    await assertCode(f.handler, event(input(changes)), 409, 'VERSION_MISMATCH')
    assert.deepEqual(f.calls.map(call => call[0]), ['identity', 'membership'])
  }
})

test('admission failures and private upstream errors never reach the model or leak details', async () => {
  for (const [error, status, code] of [
    [new QuotaError('RATE_LIMITED'), 429, 'RATE_LIMITED'],
    [new QuotaError('BUDGET_EXHAUSTED'), 429, 'BUDGET_EXHAUSTED'],
    [new Error(`private uid request ${PROVIDER_KEY}`), 503, 'SERVICE_UNAVAILABLE'],
  ]) {
    const f = fixture({ reserveQuota: async () => {
      throw error
    } })
    const result = await assertCode(f.handler, event(), status, code)
    assert.ok(!result.body.includes(PROVIDER_KEY))
    assert.ok(!f.calls.some(call => call[0] === 'model'))
  }
})

test('strict model contract rejects unknown nested fields, recipe IDs, illegal values, conflicts and unknown goals', async () => {
  const valid = intent()
  const edits = [
    value => ({ ...value, recipeIds: [1] }),
    value => ({ ...value, source: 'mock' }),
    value => ({ ...value, dataVersion: 'stale' }),
    value => ({ ...value, goal: 'weight-aware' }),
    value => ({ ...value, schemaVersion: true }),
    value => ({ ...value, unresolved: ['same', 'same'] }),
    value => ({ ...value, unresolved: [' '] }),
    value => ({ ...value, unresolved: ['字'.repeat(501)] }),
    value => ({ ...value, query: { ...value.query, actions: [] } }),
    value => ({ ...value, query: { ...value.query, stuff: ['火星菜'] } }),
    value => ({ ...value, query: { ...value.query, stuff: ['鸡蛋', '鸡蛋'] } }),
    value => ({ ...value, query: { ...value.query, unknown: ['火星菜'] } }),
    value => ({ ...value, query: { ...value.query, avoid: ['鸡蛋'] } }),
    value => ({ ...value, query: { ...value.query, tool: '火星锅' } }),
    value => ({ ...value, query: { ...value.query, noSpicy: 'true' } }),
    value => ({ ...value, query: { ...value.query, difficulty: ['十分钟'] } }),
    value => ({ ...value, query: { ...value.query, methodGroups: ['爆炒'] } }),
  ]
  for (const edit of edits) {
    const f = fixture({ complete: async () => edit(structuredClone(valid)) })
    await assertCode(f.handler, event(), 502, 'INVALID_MODEL_RESPONSE')
  }
  for (const field of ['schemaVersion', 'policyVersion', 'dataVersion', 'source', 'query', 'unresolved']) {
    const value = structuredClone(valid)
    delete value[field]
    assert.throws(() => validateIntent(value, CONTRACT), IntentServiceError)
  }
  for (const field of ['stuff', 'unknown', 'avoid', 'difficulty', 'methodGroups', 'noSpicy']) {
    const value = structuredClone(valid)
    delete value.query[field]
    assert.throws(() => validateIntent(value, CONTRACT), IntentServiceError)
  }
  assert.deepEqual(validateIntent(intent({ goal: null, query: { ...valid.query, tool: null } }), CONTRACT).goal, null)
})

test('production HTTP adapters forward user bearer only to fixed platform endpoints and model key only to provider', async () => {
  const network = networkFixture({}, { getModel: () => {
    throw new Error('Direct mode must not initialize CloudBase AI')
  } })
  const f = fixture(network)
  assert.equal((await f.handler(event())).statusCode, 200)
  assert.deepEqual(network.requests.map(request => request.url), [IDENTITY_URL, ACCOUNT_URL, MODEL_URL])
  assert.equal(network.requests[0].options.headers.Authorization, `Bearer ${USER_TOKEN}`)
  assert.equal(network.requests[1].options.headers.Authorization, `Bearer ${USER_TOKEN}`)
  assert.deepEqual(JSON.parse(network.requests[1].options.body), { action: 'getAccount' })
  const provider = network.requests[2].options
  assert.equal(provider.headers.Authorization, `Bearer ${PROVIDER_KEY}`)
  const body = JSON.parse(provider.body)
  assert.equal(body.model, 'deepseek-flash')
  assert.equal(body.max_tokens, 2048)
  assert.deepEqual(body.thinking, { type: 'disabled' })
  assert.deepEqual(body.response_format, { type: 'json_object' })
  assert.equal(body.stream, false)
  assert.deepEqual(body.messages.map(message => message.role), ['system', 'user'])
  assert.equal(body.messages[1].content, input().text)
  assert.ok(body.messages[0].content.includes(JSON.stringify(CONTRACT)))
  assert.ok(body.messages[0].content.includes('never infer it from 减肥, 减脂, 低热量'))
  for (const secret of [USER_TOKEN, PROVIDER_KEY, 'verified-user', 'private-favorite'])
    assert.ok(!provider.body.includes(secret), secret)
})

test('direct DeepSeek uses its configured model without initializing CloudBase AI', async () => {
  const config = readConfig({ ...ENV, COOK_AI_PROVIDER: 'deepseek', COOK_AI_DEEPSEEK_MODEL: 'deepseek-chat' })
  const network = networkFixture({}, { config, getModel: () => {
    throw new Error('Unexpected CloudBase AI call')
  } })
  assert.equal((await fixture(network).handler(event())).statusCode, 200)
  assert.equal(JSON.parse(network.requests[2].options.body).model, 'deepseek-chat')
})

test('CloudBase uses the selected model and shared contract without forwarding account or DeepSeek credentials', async () => {
  for (const model of ['deepseek-v4.1-flash', 'deepseek-v4.1-flash-1017']) {
    const config = readConfig({ ...ENV, COOK_AI_PROVIDER: 'cloudbase', COOK_AI_CLOUDBASE_MODEL: model })
    const network = cloudbaseFixture({ config })
    const f = fixture(network)
    const result = await f.handler(event())
    assert.equal(result.statusCode, 200)
    assert.deepEqual(JSON.parse(result.body), { ok: true, intent: intent() })
    assert.deepEqual(network.requests.map(request => request.url), [IDENTITY_URL, ACCOUNT_URL])
    assert.deepEqual(network.modelCalls.map(call => call[0]), ['init', 'generate'])
    const request = network.modelCalls[1][1]
    assert.equal(request.model, model)
    assert.equal(request.max_tokens, 2048)
    assert.equal(request.maxSteps, 1)
    assert.deepEqual(request.thinking, { type: 'disabled' })
    assert.deepEqual(request.response_format, { type: 'json_object' })
    assert.equal(request.stream, false)
    assert.deepEqual(request.messages.map(message => message.role), ['system', 'user'])
    assert.equal(request.messages[1].content, input().text)
    assert.ok(request.messages[0].content.includes(JSON.stringify(CONTRACT)))
    for (const secret of [USER_TOKEN, PROVIDER_KEY, ENV.COOK_AI_QUOTA_SALT, 'verified-user'])
      assert.ok(!JSON.stringify(request).includes(secret))
    assert.deepEqual(f.calls, [['quota', { uid: 'verified-user', now: NOW }]])
  }
})

test('CloudBase initialization waits for valid configuration, identity, membership, contract and quota', async () => {
  const cases = [
    [{ config: readConfig({ ...ENV, COOK_AI_PROVIDER: 'invalid' }) }, event(), 503, 'SERVICE_UNAVAILABLE'],
    [{}, event(input(), {}), 401, 'LOGIN_REQUIRED'],
    [{ verifyIdentity: async () => { throw new IntentServiceError('LOGIN_REQUIRED') } }, event(), 401, 'LOGIN_REQUIRED'],
    [{ readMembership: async () => ({ isActive: false, expireAt: null }) }, event(), 403, 'MEMBERSHIP_REQUIRED'],
    [{ contract: null }, event(), 503, 'SERVICE_UNAVAILABLE'],
    [{}, event(input({ dataVersion: 'old' })), 409, 'VERSION_MISMATCH'],
    [{ reserveQuota: async () => { throw new QuotaError('BUDGET_EXHAUSTED') } }, event(), 429, 'BUDGET_EXHAUSTED'],
    [{ now: () => NOW + 60_000 }, event(), 403, 'MEMBERSHIP_REQUIRED'],
  ]
  for (const [changes, request, status, code] of cases) {
    const network = cloudbaseFixture()
    await assertCode(fixture({ ...network, ...changes }).handler, request, status, code)
    assert.deepEqual(network.modelCalls, [])
    assert.ok(network.requests.every(request => request.url !== MODEL_URL))
  }
  const network = cloudbaseFixture()
  assert.equal((await fixture(network).handler(event({ action: 'capabilities' }))).statusCode, 200)
  assert.deepEqual(network.modelCalls, [])
})

test('CloudBase rejects truncated, oversized, tool and invalid JSON responses through the shared validation', async () => {
  for (const result of [
    null,
    {},
    { text: JSON.stringify(intent()) },
    { rawResponses: [] },
    { rawResponses: [envelope(), envelope()] },
    { rawResponses: [null] },
    { rawResponses: [envelope(intent(), { finish_reason: 'length' })] },
    { rawResponses: [envelope(intent(), { message: { role: 'tool', content: '{}' } })] },
    { rawResponses: [envelope(intent(), { message: { role: 'assistant', content: '{}', tool_calls: [] } })] },
    { rawResponses: [envelope(intent(), { message: { role: 'assistant', content: '{' } })] },
    { rawResponses: [envelope(intent(), { message: { role: 'assistant', content: '字'.repeat(11_000) } })] },
    { rawResponses: [{ ...envelope(), padding: 'x'.repeat(65_536) }] },
    { rawResponses: [envelope(intent({ source: 'cloudbase' }))] },
    { rawResponses: [envelope(intent({ query: { ...intent().query, stuff: ['火星菜'] } }))] },
  ]) {
    const network = cloudbaseFixture({ generateText: async () => result })
    const f = fixture(network)
    await assertCode(f.handler, event(), 502, 'INVALID_MODEL_RESPONSE')
    assert.deepEqual(network.modelCalls.map(call => call[0]), ['init', 'generate'])
    assert.deepEqual(network.requests.map(request => request.url), [IDENTITY_URL, ACCOUNT_URL])
    assert.equal(f.calls.filter(call => call[0] === 'quota').length, 1)
  }
})

test('CloudBase SDK errors are redacted and mapped without retries or a direct-provider fallback', async () => {
  for (const [error, status, code] of [
    [new DOMException('private timeout', 'TimeoutError'), 504, 'MODEL_TIMEOUT'],
    [Object.assign(new Error(`private ${PROVIDER_KEY}`), { code: 'ETIMEDOUT' }), 504, 'MODEL_TIMEOUT'],
    [Object.assign(new Error('private timeout'), { code: '504' }), 504, 'MODEL_TIMEOUT'],
    [Object.assign(new Error('private rate limit'), { code: '429' }), 429, 'RATE_LIMITED'],
    [Object.assign(new Error('private rate limit'), { code: 'EXCEED_CONCURRENT_REQUEST_LIMIT' }), 429, 'RATE_LIMITED'],
    [Object.assign(new Error(`private ${USER_TOKEN}`), { code: 'UNAUTHORIZED' }), 503, 'SERVICE_UNAVAILABLE'],
    [new Error(`private ${PROVIDER_KEY}`), 503, 'SERVICE_UNAVAILABLE'],
  ]) {
    for (const generateText of [async () => {
      throw error
    }, async () => ({ error })]) {
      const network = cloudbaseFixture({ generateText })
      const f = fixture(network)
      await assertCode(f.handler, event(), status, code)
      assert.deepEqual(network.modelCalls.map(call => call[0]), ['init', 'generate'])
      assert.deepEqual(network.requests.map(request => request.url), [IDENTITY_URL, ACCOUNT_URL])
      assert.equal(f.calls.filter(call => call[0] === 'quota').length, 1)
    }
  }
})

test('switching provider shares the same quota and cannot create a second daily budget', async () => {
  const db = new FakeDatabase()
  const config = { ...CONFIG, dailyRequestBudget: 1 }
  const reserveQuota = createQuotaReservation(db, config)
  assert.equal((await fixture({ ...networkFixture(), reserveQuota }).handler(event())).statusCode, 200)
  const network = cloudbaseFixture()
  await assertCode(fixture({ ...network, reserveQuota }).handler, event(), 429, 'BUDGET_EXHAUSTED')
  assert.deepEqual(network.modelCalls, [])
  assert.equal([...db.rows.values()].find(row => row.scope === 'global-day').count, 1)
})

test('weight awareness is accepted only when the trusted contract explicitly offers it', () => {
  const value = intent({ goal: 'weight-aware' })
  assert.throws(() => validateIntent(value, CONTRACT), IntentServiceError)
  const enabled = { ...CONTRACT, availableGoals: ['weight-aware', ...CONTRACT.availableGoals] }
  assert.equal(validateIntent(value, enabled).goal, 'weight-aware')
  assert.throws(() => validateIntent({ ...value, calories: 400 }, enabled), IntentServiceError)
})

test('production identity gate rejects anonymous, missing UID, unusable status and redirects without any account or model call', async () => {
  for (const status of [400, 401, 403]) {
    const network = networkFixture({ [IDENTITY_URL]: () => jsonResponse({ error: 'invalid bearer' }, { status }) })
    await assertCode(fixture(network).handler, event(), 401, 'LOGIN_REQUIRED')
    assert.equal(network.requests.length, 1)
  }
  for (const profile of [{ sub: 'u', is_anonymous: true }, { sub: 'u', isAnonymous: true }, { sub: 'u', role: 'anonymous' }, { sub: 'u', user_metadata: { is_anonymous: true } }, { sub: 'u', status: 'BLOCKED' }, { sub: 'u', status: 'PENDING' }, {}, { sub: 'service_account' }, { sub: '' }]) {
    const network = networkFixture({ [IDENTITY_URL]: () => jsonResponse(profile) })
    await assertCode(fixture(network).handler, event(), 401, 'LOGIN_REQUIRED')
    assert.equal(network.requests.length, 1)
  }
  for (const status of [undefined, 'DEFAULT', 'active']) {
    const network = networkFixture({ [IDENTITY_URL]: () => jsonResponse({ sub: 'verified-user', status }) })
    assert.equal((await fixture(network).handler(event())).statusCode, 200)
  }
  const network = networkFixture({ [IDENTITY_URL]: () => jsonResponse({}, { url: 'https://evil.example', redirected: true }) })
  await assertCode(fixture(network).handler, event(), 401, 'LOGIN_REQUIRED')
  assert.equal(network.requests.length, 1)
})

test('business account denials, missing membership and account redirects fail before quota or provider', async () => {
  for (const makeResponse of [
    () => jsonResponse({ code: 'account_banned', error: `private ${USER_TOKEN}` }, { status: 403 }),
    () => jsonResponse({ code: 'account_deletion_pending' }, { status: 400 }),
    () => jsonResponse({ error: 'missing membership' }),
    () => jsonResponse({ membership: { isActive: true, expireAt: 'bad' } }),
    () => jsonResponse({}, { url: 'https://evil.example', redirected: true }),
  ]) {
    const network = networkFixture({ [ACCOUNT_URL]: makeResponse })
    const f = fixture(network)
    await assertCode(f.handler, event(), 403, 'ACCOUNT_UNAVAILABLE')
    assert.deepEqual(network.requests.map(request => request.url), [IDENTITY_URL, ACCOUNT_URL])
    assert.deepEqual(f.calls, [])
  }
})

test('provider errors, truncation, malformed output and redirects are redacted and never retried', async () => {
  const cases = [
    [() => jsonResponse({ error: `private ${PROVIDER_KEY}` }, { status: 401 }), 503, 'SERVICE_UNAVAILABLE'],
    [() => jsonResponse({}, { status: 429 }), 429, 'RATE_LIMITED'],
    [() => jsonResponse({}, { status: 504 }), 504, 'MODEL_TIMEOUT'],
    [() => jsonResponse(envelope(intent(), { finish_reason: 'length' })), 502, 'INVALID_MODEL_RESPONSE'],
    [() => jsonResponse(envelope(intent(), { message: { role: 'tool', content: '{}' } })), 502, 'INVALID_MODEL_RESPONSE'],
    [() => new Response('{'), 502, 'INVALID_MODEL_RESPONSE'],
    [() => new Response('x'.repeat(65_537)), 502, 'INVALID_MODEL_RESPONSE'],
    [() => jsonResponse(envelope(), { url: 'https://evil.example', redirected: true }), 502, 'INVALID_MODEL_RESPONSE'],
    [() => { throw new DOMException('private timeout', 'TimeoutError') }, 504, 'MODEL_TIMEOUT'],
    [() => new Response(new ReadableStream({ start(controller) { controller.error(new DOMException('body timeout', 'AbortError')) } })), 504, 'MODEL_TIMEOUT'],
    [() => { throw new Error(`network private ${PROVIDER_KEY}`) }, 503, 'SERVICE_UNAVAILABLE'],
  ]
  for (const [provider, status, code] of cases) {
    const network = networkFixture({ [MODEL_URL]: provider })
    const f = fixture(network)
    const result = await assertCode(f.handler, event(), status, code)
    assert.ok(!result.body.includes(PROVIDER_KEY))
    assert.equal(network.requests.filter(request => request.url === MODEL_URL).length, 1)
    assert.equal(f.calls.filter(call => call[0] === 'quota').length, 1)
  }
})

test('concurrent atomic admission cannot exceed a daily account quota or partially increment global counters', async () => {
  const db = new FakeDatabase()
  const reserve = createQuotaReservation(db, { ...CONFIG, userDailyLimit: 10, userMinuteLimit: 60 })
  const results = await Promise.allSettled(Array.from({ length: 30 }, () => reserve({ uid: 'same-user', now: NOW })))
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 10)
  assert.equal(results.filter(result => result.status === 'rejected' && result.reason.code === 'RATE_LIMITED').length, 20)
  assert.equal(db.rows.size, 3)
  assert.ok([...db.rows.values()].every(row => row.count === 10))
  assert.ok([...db.rows.values()].every(row => row.expiresAt instanceof Date))
  assert.ok([...db.rows.keys()].every(id => /^[a-f0-9]{64}$/.test(id)))
  assert.ok(!JSON.stringify([...db.rows]).includes('same-user'))
})

test('global daily budget is shared across accounts and quota reservations are never refunded after provider failure', async () => {
  const db = new FakeDatabase()
  const config = { ...CONFIG, userDailyLimit: 100, userMinuteLimit: 60, dailyRequestBudget: 7 }
  const reserve = createQuotaReservation(db, config)
  const results = await Promise.allSettled(Array.from({ length: 20 }, (_, index) => reserve({ uid: `user-${index}`, now: NOW })))
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 7)
  assert.equal(results.filter(result => result.status === 'rejected' && result.reason.code === 'BUDGET_EXHAUSTED').length, 13)
  assert.equal([...db.rows.values()].find(row => row.scope === 'global-day').count, 7)
  const failedDb = new FakeDatabase()
  const f = fixture({
    reserveQuota: createQuotaReservation(failedDb, { ...CONFIG, dailyRequestBudget: 1 }),
    complete: async () => { throw new IntentServiceError('MODEL_TIMEOUT') },
  })
  await assertCode(f.handler, event(), 504, 'MODEL_TIMEOUT')
  await assertCode(f.handler, event(), 429, 'BUDGET_EXHAUSTED')
  assert.equal([...failedDb.rows.values()].find(row => row.scope === 'global-day').count, 1)
})

test('per-minute limit, China day rollover, missing collections and corrupt counters fail safely', async () => {
  const db = new FakeDatabase()
  const reserve = createQuotaReservation(db, { ...CONFIG, userMinuteLimit: 1, userDailyLimit: 1 })
  const midnight = Date.parse('2026-10-29T16:00:00Z')
  await reserve({ uid: 'user', now: midnight - 1 })
  await assert.rejects(reserve({ uid: 'user', now: midnight - 1 }), error => error.code === 'RATE_LIMITED')
  await reserve({ uid: 'user', now: midnight })
  assert.equal([...db.rows.values()].filter(row => row.scope === 'user-day').length, 2)
  const missing = new FakeDatabase()
  missing.missingCollection = true
  await assert.rejects(createQuotaReservation(missing, CONFIG)({ uid: 'user', now: NOW }), error => error.code === 'SERVICE_UNAVAILABLE')
  assert.equal(missing.rows.size, 0)
  const corrupt = new FakeDatabase()
  const globalId = createHash('sha256').update('global:2026-10-29').digest('hex')
  corrupt.rows.set(globalId, { scope: 'global-day', window: '2026-10-29', count: 'bad' })
  await assert.rejects(createQuotaReservation(corrupt, CONFIG)({ uid: 'user', now: NOW }), error => error.code === 'SERVICE_UNAVAILABLE')
  assert.equal(corrupt.rows.size, 1)
})

test('only proven transaction conflicts are retried and at most three transaction attempts occur', async () => {
  const conflict = () => Object.assign(new Error('conflict'), { code: 'ResourceUnavailable.TransactionBusy' })
  const db = new FakeDatabase()
  db.failures = [conflict(), conflict()]
  await createQuotaReservation(db, CONFIG)({ uid: 'user', now: NOW })
  assert.equal(db.attempts, 3)
  const unavailable = new FakeDatabase()
  unavailable.failures = [conflict(), conflict(), conflict()]
  await assert.rejects(createQuotaReservation(unavailable, CONFIG)({ uid: 'user', now: NOW }), error => error.code === 'SERVICE_UNAVAILABLE')
  assert.equal(unavailable.attempts, 3)
  assert.equal(unavailable.rows.size, 0)
  const failure = new FakeDatabase()
  failure.failures = [new Error('private missing collection')]
  await assert.rejects(createQuotaReservation(failure, CONFIG)({ uid: 'user', now: NOW }), error => error.code === 'SERVICE_UNAVAILABLE')
  assert.equal(failure.attempts, 1)
})

test('expired counter cleanup is bounded and deletes explicit expired IDs only', async () => {
  const rows = [
    ...Array.from({ length: 150 }, (_, index) => ({
      _id: createHash('sha256').update(`expired-${index}`).digest('hex'),
      expiresAt: new Date(NOW - 1),
    })),
  ]
  const removed = []
  const queries = []
  const database = {
    command: { lt: value => ({ lessThan: value }) },
    collection(name) {
      assert.equal(name, COLLECTION)
      return {
        where(query) {
          queries.push(query)
          return { limit(limit) {
            assert.equal(limit, 100)
            // Exercise the helper's own bound even if a database unexpectedly returns extra rows.
            return { get: async () => ({ data: rows }) }
          } }
        },
        doc(id) {
          return { remove: async () => {
            removed.push(id)
          } }
        },
      }
    },
  }
  assert.equal(await cleanupExpiredQuota(database, NOW), 100)
  assert.equal(removed.length, 100)
  assert.deepEqual(queries, [{ expiresAt: { lessThan: new Date(NOW) } }])
  rows.splice(0, rows.length, { _id: 'a'.repeat(64), expiresAt: new Date(NOW + 1) }, { _id: 'b'.repeat(64), expiresAt: new Date(NOW) }, { _id: 'c'.repeat(64), expiresAt: NOW - 1 }, { _id: 'd'.repeat(64), expiresAt: new Date('invalid') }, { _id: 'untrusted-id', expiresAt: new Date(NOW - 1) }, { _id: 'e'.repeat(64), expiresAt: new Date(NOW - 1) })
  removed.length = 0
  assert.equal(await cleanupExpiredQuota(database, NOW), 1)
  assert.deepEqual(removed, ['e'.repeat(64)])
})
