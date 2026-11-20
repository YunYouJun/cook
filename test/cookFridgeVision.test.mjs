import assert from 'node:assert/strict'
import { Buffer } from 'node:buffer'
// eslint-disable-next-line test/no-import-node-test -- The independently deployed function is tested without Vitest or Nuxt.
import { test } from 'node:test'
import vision from '../cloudfunctions/cook-fridge-vision/index.js'
import quota from '../cloudfunctions/cook-fridge-vision/quota.js'

const { createHandler, _internal: { ACCOUNT_URL, IDENTITY_URL, MAX_JPEG_BYTES, createAdapters, readConfig } } = vision
const { VisionError, reserveQuota } = quota
const qwenURL = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
const deepSeekURL = 'https://api.deepseek.com/chat/completions'
const qwenConfig = { enabled: true, provider: 'qwen' }
const image = Buffer.from([0xFF, 0xD8, 0x20, 0xFF, 0xD9]).toString('base64')
const token = 'test-account-token'
const apiKey = 'test-provider-key'
const activeMembership = { isActive: true, expireAt: Number.MAX_SAFE_INTEGER }

const readActiveMembership = async () => activeMembership

function event(fields) {
  return {
    httpMethod: 'POST',
    headers: { 'Authorization': `Bearer ${token}`, 'x-cloudbase-context': 'private-context' },
    body: JSON.stringify({ schemaVersion: 1, consentVersion: 1, provider: 'qwen', image, ...fields }),
  }
}

test('disabled or partially configured deployment cannot call the vision model', async () => {
  const env = { COOK_FRIDGE_VISION_ENABLED: 'true', COOK_FRIDGE_VISION_API_KEY: apiKey }
  assert.equal(readConfig(env).enabled, false)
  env.COOK_FRIDGE_VISION_QUOTA_SALT = 'x'.repeat(32)
  assert.equal(readConfig(env).enabled, true)
  assert.equal(readConfig({ ...env, COOK_FRIDGE_VISION_DAILY_BUDGET: '5001' }).enabled, false)
  assert.equal(readConfig({ ...env, COOK_FRIDGE_VISION_PROVIDER: 'other' }).enabled, false)
  const deepSeekEnv = { ...env, COOK_FRIDGE_VISION_PROVIDER: 'deepseek' }
  assert.equal(readConfig(deepSeekEnv).enabled, false, 'The Qwen key is never used for DeepSeek')
  assert.equal(readConfig({ ...deepSeekEnv, COOK_FRIDGE_VISION_DEEPSEEK_API_KEY: 'test-deepseek-key' }).enabled, true)
  const cloudBaseEnv = { ...env, COOK_FRIDGE_VISION_PROVIDER: 'cloudbase', COOK_FRIDGE_VISION_API_KEY: undefined }
  assert.equal(readConfig(cloudBaseEnv).enabled, true)
  assert.equal(readConfig({ ...cloudBaseEnv, COOK_FRIDGE_VISION_QUOTA_SALT: undefined }).enabled, false)
  const handler = createHandler({ config: readConfig({}) })
  assert.equal((await handler(event())).statusCode, 503)
})

test('photo is withheld without consent, valid identity and quota', async () => {
  let identityAttempts = 0
  let admissions = 0
  let modelCalls = 0
  const handler = createHandler({
    config: qwenConfig,
    async verifyIdentity() {
      identityAttempts++
      throw new VisionError('LOGIN_REQUIRED')
    },
    async admit() { admissions++ },
    async recognize() { modelCalls++ },
  })
  for (const input of [
    event({ consentVersion: undefined }),
    event({ consentVersion: 2 }),
    event({ provider: undefined }),
    event({ image: 'https://example.invalid/photo' }),
    event({ image: Buffer.alloc(MAX_JPEG_BYTES + 1).toString('base64') }),
    event({ image: Buffer.from('not a jpeg').toString('base64') }),
  ]) {
    assert.equal((await handler(input)).statusCode, 400)
  }
  assert.equal((await handler({ ...event(), headers: {} })).statusCode, 401)
  assert.equal(identityAttempts, 0)
  assert.equal((await handler(event())).statusCode, 401)
  assert.equal(identityAttempts, 1)
  assert.equal(admissions, 0)
  const overBudget = createHandler({
    config: qwenConfig,
    async verifyIdentity() { return 'verified-user' },
    readMembership: readActiveMembership,
    async admit() { throw new VisionError('BUDGET_EXHAUSTED') },
    async recognize() { modelCalls++ },
  })
  assert.equal((await overBudget(event())).statusCode, 429)
  assert.equal(modelCalls, 0)
})

test('provider receives only the photo and its own credentials; client receives only names', async () => {
  const requests = []
  const adapters = createAdapters({ ...qwenConfig, apiKey }, async (url, options) => {
    requests.push({ url, options })
    if (url === IDENTITY_URL)
      return Response.json({ sub: 'account-123', status: 'ACTIVE' })
    if (url === ACCOUNT_URL)
      return Response.json({ membership: activeMembership })
    return Response.json({ choices: [{ finish_reason: 'stop', message: { content: '```json\n{"ingredients":["鸡蛋","番茄","鸡蛋"]}\n```' } }] })
  })
  const handler = createHandler({
    config: qwenConfig,
    ...adapters,
    async admit(uid) {
      assert.equal(uid, 'account-123')
      assert.equal(requests.length, 2)
    },
  })
  const result = await handler(event())
  assert.equal(result.statusCode, 200)
  assert.deepEqual(JSON.parse(result.body), { ok: true, schemaVersion: 1, ingredients: ['鸡蛋', '番茄'] })
  assert.deepEqual(requests.map(item => item.url), [IDENTITY_URL, ACCOUNT_URL, qwenURL])
  assert.equal(requests[0].options.headers.Authorization, `Bearer ${token}`)
  assert.equal(requests[0].options.body, undefined)
  assert.equal(requests[1].options.headers.Authorization, `Bearer ${token}`)
  assert.deepEqual(JSON.parse(requests[1].options.body), { action: 'getAccount' })
  const outbound = requests[2].options
  assert.equal(outbound.headers.Authorization, `Bearer ${apiKey}`)
  assert.equal(outbound.redirect, 'error')
  assert(!outbound.body.includes(token) && !outbound.body.includes('private-context'))
  assert.deepEqual(JSON.parse(outbound.body).messages[0].content[0], {
    type: 'image_url',
    image_url: { url: `data:image/jpeg;base64,${image}` },
  })
})

test('DeepSeek receives the photo only after consent names DeepSeek; discovery and stale consent spend no quota', async () => {
  let admissions = 0
  const modelRequests = []
  const config = { enabled: true, provider: 'deepseek', apiKey: 'test-deepseek-key' }
  const adapters = createAdapters(config, async (url, options) => {
    if (url === IDENTITY_URL)
      return Response.json({ sub: 'verified-user' })
    if (url === ACCOUNT_URL)
      return Response.json({ membership: activeMembership })
    modelRequests.push({ url, options })
    return Response.json({ choices: [{ finish_reason: 'stop', message: { content: '{"ingredients":["鸡蛋","牛奶"]}' } }] })
  })
  const handler = createHandler({
    config,
    ...adapters,
    async admit() { admissions++ },
  })
  const discovery = await handler({ httpMethod: 'GET' })
  assert.equal(discovery.statusCode, 200)
  assert.equal(discovery.headers['Cache-Control'], 'no-store')
  assert.deepEqual(JSON.parse(discovery.body), { ok: true, schemaVersion: 1, provider: 'deepseek' })
  const stale = await handler(event())
  assert.equal(stale.statusCode, 409)
  assert.deepEqual(JSON.parse(stale.body), { ok: false, code: 'PROVIDER_CHANGED' })
  assert.equal(admissions, 0)
  assert.equal(modelRequests.length, 0)

  const accepted = await handler(event({ provider: 'deepseek' }))
  assert.deepEqual(JSON.parse(accepted.body), { ok: true, schemaVersion: 1, ingredients: ['鸡蛋', '牛奶'] })
  assert.equal(admissions, 1)
  assert.equal(modelRequests.length, 1)
  const [{ url, options }] = modelRequests
  assert.equal(url, deepSeekURL)
  assert.equal(options.headers.Authorization, 'Bearer test-deepseek-key')
  assert(!options.body.includes(token))
  const body = JSON.parse(options.body)
  assert.equal(body.model, 'deepseek-flash')
  assert.deepEqual(body.thinking, { type: 'disabled' })
  assert.deepEqual(body.response_format, { type: 'json_object' })
  assert.deepEqual(body.messages[0].content[1], { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${image}` } })
})

test('CloudBase vision uses the managed model and requires consent for that channel', async () => {
  const config = { enabled: true, provider: 'cloudbase' }
  const modelRequests = []
  const accountRequests = []
  let admissions = 0
  const adapters = createAdapters(
    config,
    async (url) => {
      accountRequests.push(url)
      assert(url === IDENTITY_URL || url === ACCOUNT_URL)
      return url === IDENTITY_URL
        ? Response.json({ sub: 'verified-user' })
        : Response.json({ membership: activeMembership })
    },
    () => ({
      async generateText(request) {
        modelRequests.push(request)
        return { rawResponses: [{ choices: [{ finish_reason: 'stop', message: { content: '{"ingredients":["鸡蛋","番茄"]}' } }] }] }
      },
    }),
  )
  const handler = createHandler({
    config,
    ...adapters,
    async admit() { admissions++ },
  })
  const discovery = await handler({ httpMethod: 'GET', headers: event().headers })
  assert.deepEqual(JSON.parse(discovery.body), { ok: true, schemaVersion: 1, provider: 'cloudbase', member: true })

  const stale = await handler(event({ provider: 'deepseek' }))
  assert.equal(stale.statusCode, 409)
  assert.equal(admissions, 0)
  assert.equal(modelRequests.length, 0)

  const result = await handler(event({ provider: 'cloudbase' }))
  assert.equal(result.statusCode, 200)
  assert.deepEqual(JSON.parse(result.body), { ok: true, schemaVersion: 1, ingredients: ['鸡蛋', '番茄'] })
  assert.deepEqual(accountRequests, [IDENTITY_URL, ACCOUNT_URL, IDENTITY_URL, ACCOUNT_URL])
  assert.equal(admissions, 1)
  assert.equal(modelRequests.length, 1)
  const [request] = modelRequests
  assert.equal(request.model, 'deepseek-v4.1-flash-1017')
  assert.equal(request.maxSteps, 1)
  assert.deepEqual(request.thinking, { type: 'disabled' })
  assert.deepEqual(request.response_format, { type: 'json_object' })
  assert.deepEqual(request.messages[0].content[1], { type: 'image_url', image_url: { url: `data:image/jpeg;base64,${image}` } })
  assert(!JSON.stringify(request).includes(token) && !JSON.stringify(request).includes('private-context'))
})

test('CloudBase failures produce bounded error responses and do not fall back to direct API calls', async () => {
  for (const [reply, statusCode, code] of [
    [{ rawResponses: [] }, 502, 'INVALID_MODEL_RESPONSE'],
    [{ rawResponses: [{ choices: [{ finish_reason: 'length', message: { content: '{"ingredients":[]}' } }] }] }, 502, 'INVALID_MODEL_RESPONSE'],
    [{ error: { code: 'ETIMEDOUT', message: 'provider error detail' } }, 504, 'MODEL_TIMEOUT'],
    [{ error: { code: 'EXCEED_REQUEST_LIMIT', message: 'provider error detail' } }, 429, 'RATE_LIMITED'],
  ]) {
    const config = { enabled: true, provider: 'cloudbase' }
    let directCalls = 0
    const { recognize } = createAdapters(
      config,
      async () => { directCalls++ },
      () => ({ generateText: async () => reply }),
    )
    const handler = createHandler({ config, verifyIdentity: async () => 'test', readMembership: readActiveMembership, admit: async () => {}, recognize })
    const result = await handler(event({ provider: 'cloudbase' }))
    assert.equal(result.statusCode, statusCode)
    assert.deepEqual(JSON.parse(result.body), { ok: false, code })
    assert.equal(directCalls, 0)
  }
})

test('eligibility is established without a photo; nonmembers and expired members cannot spend quota or reach a model', async () => {
  const now = Date.UTC(2026, 10, 17)
  for (const membership of [{ isActive: false, expireAt: null }, { isActive: true, expireAt: now }]) {
    const requests = []
    const adapters = createAdapters({ ...qwenConfig, apiKey }, async (url) => {
      requests.push(url)
      return url === IDENTITY_URL ? Response.json({ sub: 'nonmember' }) : Response.json({ membership })
    })
    let admissions = 0
    const handler = createHandler({
      config: qwenConfig,
      ...adapters,
      async admit() { admissions++ },
      now: () => now,
    })
    const capability = await handler({ httpMethod: 'GET', headers: { Authorization: `Bearer ${token}` } })
    assert.equal(capability.statusCode, 200)
    assert.equal(capability.headers.Vary, 'Authorization')
    assert.deepEqual(JSON.parse(capability.body), { ok: true, schemaVersion: 1, provider: 'qwen', member: false })
    const denied = await handler(event())
    assert.equal(denied.statusCode, 403)
    assert.deepEqual(JSON.parse(denied.body), { ok: false, code: 'MEMBERSHIP_REQUIRED' })
    assert.equal(admissions, 0)
    assert.deepEqual(requests, [IDENTITY_URL, ACCOUNT_URL, IDENTITY_URL, ACCOUNT_URL])
  }
})

test('membership lookup failure and expiration during quota admission fail closed', async () => {
  let admitCalls = 0
  let providerCalls = 0
  let now = Date.UTC(2026, 10, 17)
  const expireAt = now + 1
  const dependencies = {
    config: qwenConfig,
    async verifyIdentity() { return 'member' },
    async admit() {
      admitCalls++
      now = expireAt
    },
    async recognize() {
      providerCalls++
      return []
    },
    now: () => now,
  }
  const lookupFailed = createHandler({
    ...dependencies,
    async readMembership() { throw new VisionError('SERVICE_UNAVAILABLE') },
  })
  assert.equal((await lookupFailed(event())).statusCode, 503)
  assert.equal(admitCalls, 0)
  const expiring = createHandler({
    ...dependencies,
    async readMembership() { return { isActive: true, expireAt } },
  })
  const capability = await expiring({ httpMethod: 'GET', headers: event().headers })
  assert.equal(JSON.parse(capability.body).member, true)
  assert.equal(admitCalls, 0)
  const denied = await expiring(event())
  assert.equal(denied.statusCode, 403)
  assert.equal(admitCalls, 1)
  assert.equal(providerCalls, 0)
})

test('empty results are valid; extra fields, unbounded output and provider failures do not leak', async () => {
  const reply = (content, finish_reason = 'stop') => Response.json({ choices: [{ finish_reason, message: { content } }] })
  for (const [replyFactory, expected, code] of [
    [() => reply('{"ingredients":[]}'), 200, undefined],
    [() => reply('{"ingredients":["牛奶"],"expiry":"2026-11-20"}'), 502, 'INVALID_MODEL_RESPONSE'],
    [() => reply('{"ingredients":["AI 识图\n内部信息"]}'), 502, 'INVALID_MODEL_RESPONSE'],
    [() => reply('{"ingredients":["鸡蛋"]}', 'length'), 502, 'INVALID_MODEL_RESPONSE'],
    [() => new Response('provider secret error', { status: 500 }), 503, 'SERVICE_UNAVAILABLE'],
    [() => { throw new DOMException('provider timeout detail', 'TimeoutError') }, 504, 'MODEL_TIMEOUT'],
  ]) {
    const { recognize } = createAdapters({ ...qwenConfig, apiKey }, replyFactory)
    const handler = createHandler({ config: qwenConfig, verifyIdentity: async () => 'test', readMembership: readActiveMembership, admit: async () => {}, recognize })
    const result = await handler(event())
    assert.equal(result.statusCode, expected)
    assert.deepEqual(JSON.parse(result.body), code ? { ok: false, code } : { ok: true, schemaVersion: 1, ingredients: [] })
  }
})

test('an over-limit account does not consume space in the shared daily budget', async () => {
  const rows = new Map()
  const db = {
    async runTransaction(operation) {
      await operation({ collection: () => ({ doc: id => ({
        get: async () => ({ data: rows.get(id) }),
        set: async value => rows.set(id, value),
      }) }) })
    },
  }
  const config = { quotaSalt: 'x'.repeat(32), userDailyLimit: 1, dailyRequestBudget: 2 }
  const now = Date.UTC(2026, 10, 17)
  await reserveQuota(db, config, 'test-user-a', now)
  await assert.rejects(reserveQuota(db, config, 'test-user-a', now), { code: 'RATE_LIMITED' })
  await reserveQuota(db, config, 'test-user-b', now)
  await assert.rejects(reserveQuota(db, config, 'test-user-c', now), { code: 'BUDGET_EXHAUSTED' })
  assert.equal(rows.size, 3)
  assert.deepEqual([...rows.values()].map(row => row.count).sort(), [1, 1, 2])
  assert([...rows.keys()].every(id => /^[0-9a-f]{64}$/.test(id)))
})
