import { Buffer } from 'node:buffer'
import { createRequire } from 'node:module'
import { beforeEach, describe, expect, it, vi } from 'vitest'

interface FunctionModule {
  main: (event?: Record<string, unknown>) => Promise<Record<string, any>>
  _internal: {
    APP_FEEDBACK: string
    DAILY_QUOTA: number
    FEEDBACK: string
    QUOTA: string
    SIGNALS: string
    setAppForTest: (app: unknown) => void
  }
}

const require = createRequire(import.meta.url)
const fn = require('../cloudfunctions/cook-feedback/index.js') as FunctionModule
const bridge = require('../cloudfunctions/cook-feedback/support.js') as { setClientLoaderForTest: (loader: () => Promise<unknown>) => void }

const receipt = { id: 'fb_test-receipt', productId: 'cook', status: 'received', createdAt: '2026-10-23T00:00:00.000Z' }
const submit = vi.fn(async (_input: unknown, _key: string) => receipt)
const createClient = vi.fn((_options: Record<string, unknown>) => ({ submitFeedback: submit }))

type Doc = Record<string, any>

/** In-memory stand-in for the parts of the CloudBase database the function uses. */
function createApp(uid = '', target = fn) {
  const store = new Map<string, Map<string, Doc>>()
  const table = (name: string) => {
    if (!store.has(name))
      store.set(name, new Map())
    return store.get(name)!
  }
  const command = {
    inc: (n: number) => ({ $inc: n }),
    gt: (n: number) => ({ $gt: n }),
  }
  const query = (name: string, cond: Doc) => {
    let skip = 0
    let limit = Infinity
    const api = {
      skip: (n: number) => {
        skip = n
        return api
      },
      limit: (n: number) => {
        limit = n
        return api
      },
      get: async () => {
        const rows = [...table(name)].map(([id, doc]) => ({ _id: id, ...doc })).filter(doc => Object.entries(cond).every(([k, v]) => v && typeof v === 'object' && '$gt' in v ? doc[k] > v.$gt : doc[k] === v))
        return { data: rows.slice(skip, skip + limit) }
      },
    }
    return api
  }
  const collection = (name: string) => ({
    doc: (id: string) => ({
      get: async () => ({ data: table(name).has(id) ? [{ _id: id, ...table(name).get(id) }] : [] }),
      set: async (data: Doc) => {
        table(name).set(id, { ...data })
        return {}
      },
      update: async (data: Doc) => {
        const current = table(name).get(id)
        if (!current)
          return { updated: 0 }
        for (const [key, value] of Object.entries(data))
          current[key] = value && typeof value === 'object' && '$inc' in value ? (current[key] ?? 0) + value.$inc : value
        return { updated: 1 }
      },
    }),
    where: (cond: Doc) => query(name, cond),
    ...query(name, {}),
  })
  target._internal.setAppForTest({
    auth: () => ({ getUserInfo: () => ({ uid }) }),
    database: () => ({ collection, command }),
  })
  return { table }
}

const deviceId = 'device-0123456789abcdef'
const report = { action: 'report', deviceId, recipeId: 3, recipeName: '番茄炒蛋', kind: 'video' }

describe('cook-feedback function', () => {
  it('stores issue details and allowlisted diagnostics without accepting claimed user identity', async () => {
    const { table } = createApp()
    const context = { entryPoint: 'recipe-detail', origin: 'https://cook.yunle.fun', path: '/tabs/library', build: 'abc123', recipeVersion: 'def456', secret: 'never-store' }
    await fn.main({ ...report, environment: 'production', version: '2.0.0', context, linkIssue: 'login', note: '需要登录', uid: 'forged' })
    const row = [...table(fn._internal.FEEDBACK).values()][0]!
    expect(row).toMatchObject({ environment: 'production', version: '2.0.0', linkIssue: 'login', note: '需要登录', uid: '' })
    expect(row.context).not.toHaveProperty('secret')
    for (const invalid of [
      { ...context, origin: 'https://preview.example.com' },
      { ...context, origin: 'https://cook.yunle.fun?token=secret' },
      { ...context, path: '/feedback?token=secret' },
      { ...context, build: 'x'.repeat(65) },
    ]) expect(await fn.main({ ...report, context: invalid })).toEqual({ ok: false, code: 'INVALID_REPORT' })
  })

  it('rejects test traffic at the production boundary before writes, including old preview clients', async () => {
    const { table } = createApp()
    expect(await fn.main({ ...report, environment: 'test' })).toEqual({ ok: false, code: 'ENVIRONMENT_MISMATCH' })
    const result = await fn.main({ httpMethod: 'POST', headers: { Origin: 'http://localhost:3000' }, body: JSON.stringify(report) })
    expect(JSON.parse(result.body).code).toBe('ENVIRONMENT_MISMATCH')
    expect(table(fn._internal.FEEDBACK).size).toBe(0)
    expect(table(fn._internal.QUOTA).size).toBe(0)
  })

  it('isolates all test writes and never forwards test app feedback to Support', async () => {
    const modulePath = require.resolve('../cloudfunctions/cook-feedback/index.js')
    const cached = require.cache[modulePath]
    process.env.COOK_FEEDBACK_ENVIRONMENT = 'test'
    delete require.cache[modulePath]
    let isolated: FunctionModule
    try {
      isolated = require(modulePath)
    }
    finally {
      delete process.env.COOK_FEEDBACK_ENVIRONMENT
      require.cache[modulePath] = cached
    }
    const { table } = createApp('', isolated!)
    expect(await isolated!.main(report)).toEqual({ ok: false, code: 'ENVIRONMENT_MISMATCH' })
    expect(await isolated!.main({ ...report, environment: 'test' })).toEqual({ ok: true })
    await isolated!.main({ action: 'signal', deviceId, environment: 'test', type: 'empty', stuff: ['白菜'] })
    const result = await isolated!.main({ action: 'feedback', deviceId, environment: 'test', category: 'bug', note: '测试', submissionId: 'test-only-123' })
    expect(result.receipt.id).toMatch(/^test_/)
    expect(submit).not.toHaveBeenCalled()
    expect(table('cook_recipe_feedback_test').size).toBe(1)
    expect(table('cook_search_signals_test').size).toBe(1)
    expect(table('cook_app_feedback_test').size).toBe(1)
    for (const name of ['cook_recipe_feedback', 'cook_search_signals', 'cook_app_feedback', 'cook_feedback_quota'])
      expect(table(name).size).toBe(0)
  })
  it('accepts text reports with source snapshots and keeps older video clients compatible', async () => {
    const { table } = createApp()
    expect(await fn.main(report)).toEqual({ ok: true })
    expect(await fn.main({ ...report, kind: 'text', sourceBv: '', sourceLink: 'https://example.com/recipe' })).toEqual({ ok: true })
    const rows = [...table(fn._internal.FEEDBACK).values()]
    expect(rows).toHaveLength(2)
    expect(rows.find(row => row.kind === 'video')).not.toHaveProperty('sourceLink')
    expect(rows.find(row => row.kind === 'text')).toMatchObject({ sourceBv: '', sourceLink: 'https://example.com/recipe' })
  })

  it('rejects unsafe or mismatched source snapshots before any database write', async () => {
    const { table } = createApp()
    for (const snapshot of [
      { sourceBv: 'not-a-video' },
      { sourceBv: '', sourceLink: 'https://example.com/recipe' },
      { kind: 'text', sourceLink: '' },
      { kind: 'text', sourceLink: 'javascript:alert(1)' },
      { kind: 'text', sourceLink: 'https://user:pass@example.com/recipe' },
      { kind: 'text', sourceLink: 'x'.repeat(2049) },
    ]) {
      expect(await fn.main({ ...report, ...snapshot })).toEqual({ ok: false, code: 'INVALID_REPORT' })
    }
    expect(table(fn._internal.FEEDBACK).size).toBe(0)
    expect(table(fn._internal.QUOTA).size).toBe(0)
  })

  beforeEach(() => {
    delete process.env.COOK_FEEDBACK_EXPORT_TOKEN
    process.env.COOK_SUPPORT_PRIVATE_KEY = 'test-key-not-a-real-secret'
    process.env.COOK_SUPPORT_KEY_ID = 'test-key'
    submit.mockReset().mockResolvedValue(receipt)
    createClient.mockClear()
    bridge.setClientLoaderForTest(async () => ({ createSupportServerClient: createClient }))
  })

  it('stores anonymous reports keyed by device, once per recipe and kind per day', async () => {
    const { table } = createApp()
    expect(await fn.main(report)).toEqual({ ok: true })
    expect(await fn.main({ ...report, note: '视频没了' })).toEqual({ ok: true })
    const rows = [...table(fn._internal.FEEDBACK).values()]
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ recipeId: 3, kind: 'video', note: '视频没了', uid: '' })
    expect(JSON.stringify(rows[0])).not.toContain(deviceId)
  })

  it('keeps the uid for signed-in reporters', async () => {
    const { table } = createApp('user-1')
    await fn.main({ ...report, deviceId: undefined })
    expect([...table(fn._internal.FEEDBACK).values()][0]).toMatchObject({ uid: 'user-1' })
  })

  it('rejects calls without a reporter or with bad input', async () => {
    createApp()
    expect(await fn.main({ ...report, deviceId: 'short' })).toEqual({ ok: false, code: 'REPORTER_REQUIRED' })
    expect(await fn.main({ ...report, kind: 'spam' })).toEqual({ ok: false, code: 'INVALID_REPORT' })
    expect(await fn.main({ ...report, recipeId: -1 })).toEqual({ ok: false, code: 'INVALID_REPORT' })
    expect(await fn.main({ action: 'nope', deviceId })).toEqual({ ok: false, code: 'UNKNOWN_ACTION' })
  })

  it('aggregates search signals', async () => {
    const { table } = createApp()
    const signal = { action: 'signal', deviceId, type: 'open', stuff: ['鸡蛋', '番茄'], mode: 'loose', recipeId: 3, rank: 4 }
    await fn.main(signal)
    await fn.main({ ...signal, stuff: ['番茄', '鸡蛋'], rank: 2 })
    await fn.main({ action: 'signal', deviceId, type: 'empty', stuff: ['羊肉'] })
    const rows = [...table(fn._internal.SIGNALS).values()]
    expect(rows).toHaveLength(2)
    expect(rows.find(r => r.type === 'open')).toMatchObject({ stuff: ['番茄', '鸡蛋'], recipeId: 3, count: 2, rankSum: 6 })
    expect(rows.find(r => r.type === 'empty')).toMatchObject({ stuff: ['羊肉'], recipeId: -1, count: 1 })
    expect(await fn.main({ ...signal, rank: 5000 })).toEqual({ ok: false, code: 'INVALID_SIGNAL' })
  })

  it('records the platform on reports and counts signals per platform', async () => {
    const { table } = createApp()
    await fn.main(report)
    await fn.main({ ...report, deviceId: 'ios-device-0123456789', platform: 'ios', version: '2.0.0' })
    const reports = [...table(fn._internal.FEEDBACK).values()]
    expect(reports.find(r => r.platform === 'web')).toMatchObject({ version: '' })
    expect(reports.find(r => r.platform === 'ios')).toMatchObject({ version: '2.0.0' })
    expect(await fn.main({ ...report, platform: 'android' })).toEqual({ ok: false, code: 'INVALID_REPORT' })

    const empty = { action: 'signal', deviceId, type: 'empty', stuff: ['羊肉'] }
    await fn.main(empty)
    await fn.main({ ...empty, platform: 'web' })
    await fn.main({ ...empty, platform: 'ios', version: '2.0.0' })
    const signals = [...table(fn._internal.SIGNALS).entries()]
    expect(signals).toHaveLength(2)
    expect(signals.find(([, r]) => r.platform === 'web')![1]).toMatchObject({ count: 2 })
    expect(signals.find(([, r]) => r.platform === 'ios')![1]).toMatchObject({ count: 1, lastVersion: '2.0.0' })
    expect(await fn.main({ ...empty, platform: 'android' })).toEqual({ ok: false, code: 'INVALID_SIGNAL' })
  })

  it('keeps the signal key of rows stored before platforms existed', async () => {
    const { table } = createApp()
    await fn.main({ action: 'signal', deviceId, type: 'empty', stuff: ['羊肉'] })
    const [[id, row]] = [...table(fn._internal.SIGNALS).entries()]
    // An older row: same key, no platform field.
    delete row.platform
    await fn.main({ action: 'signal', deviceId, type: 'empty', stuff: ['羊肉'], platform: 'web' })
    expect(table(fn._internal.SIGNALS).size).toBe(1)
    expect(table(fn._internal.SIGNALS).get(id)).toMatchObject({ count: 2 })
  })

  it('rate limits a reporter per day', async () => {
    const { table } = createApp()
    await fn.main(report)
    const [quota] = [...table(fn._internal.QUOTA).values()]
    quota!.count = fn._internal.DAILY_QUOTA
    expect(await fn.main(report)).toEqual({ ok: false, code: 'RATE_LIMITED' })
  })

  it('exports only over HTTP with the right token', async () => {
    createApp()
    await fn.main(report)
    process.env.COOK_FEEDBACK_EXPORT_TOKEN = 'secret-token'
    expect((await fn.main({ httpMethod: 'GET', headers: { 'x-cook-token': 'wrong-token!' } })).statusCode).toBe(401)
    expect((await fn.main({ httpMethod: 'GET', headers: {} })).statusCode).toBe(401)
    const res = await fn.main({ httpMethod: 'GET', headers: { 'x-cook-token': 'secret-token' } })
    expect(res.statusCode).toBe(200)
    const body = JSON.parse(res.body)
    expect(body.feedback[0]).toMatchObject({ recipeId: 3, signedIn: false })
    expect(body.feedback[0].uid).toBeUndefined()
    expect(body.appFeedback).toEqual([])
  })

  it('sends app feedback once to Support without a second local inbox', async () => {
    const { table } = createApp()
    const message = { action: 'feedback', deviceId, category: 'idea', note: ' 希望能按季节推荐 ', contact: 'wx: cook', platform: 'ios', version: '2.0.0' }
    expect(await fn.main(message)).toEqual({ ok: true, receipt })
    expect(await fn.main(message)).toEqual({ ok: true, receipt })
    expect(table(fn._internal.APP_FEEDBACK).size).toBe(0)
    expect(submit.mock.calls[0]).toEqual(submit.mock.calls[1])
    expect(submit.mock.calls[0]![0]).toEqual({ productId: 'cook', category: 'suggestion', description: '【功能建议】\n\n希望能按季节推荐\n\n联系方式：wx: cook', context: { source: 'cook-ios', version: '2.0.0' } })
    expect(createClient.mock.calls[0]![0]).toMatchObject({ clientId: 'cook-server', guestId: expect.any(String) })
    expect(JSON.stringify(createClient.mock.calls)).not.toContain(deviceId)
    await fn.main({ ...message, contact: 'new contact' })
    expect(submit.mock.calls[2]![1]).not.toEqual(submit.mock.calls[0]![1])
  })

  it('uses verified function identity and keeps raw HTTP callers anonymous', async () => {
    createApp('verified-account')
    const message = { action: 'feedback', deviceId, category: 'bug', note: '打不开', uid: 'forged-account', userId: 'forged-account', submissionId: 'stable-submission-123' }
    await fn.main(message)
    expect(createClient.mock.calls[0]![0]).toMatchObject({ userId: 'verified-account' })
    expect(submit.mock.calls[0]![1]).toBe('stable-submission-123')
    await fn.main({ httpMethod: 'POST', body: JSON.stringify(message), headers: {} })
    expect(createClient.mock.calls[1]![0]).toMatchObject({ guestId: expect.any(String) })
    expect(createClient.mock.calls[1]![0]).not.toHaveProperty('userId')
  })

  it('fails closed and preserves historical app feedback when Support is unavailable', async () => {
    const { table } = createApp()
    table(fn._internal.APP_FEEDBACK).set('historic', { note: 'historical' })
    const message = { action: 'feedback', deviceId, category: 'bug', note: '打不开' }
    delete process.env.COOK_SUPPORT_PRIVATE_KEY
    expect(await fn.main(message)).toEqual({ ok: false, code: 'SUPPORT_UNAVAILABLE' })
    expect(submit).not.toHaveBeenCalled()
    process.env.COOK_SUPPORT_PRIVATE_KEY = 'test'
    submit.mockRejectedValueOnce(new Error('network failure'))
    const response = await fn.main({ httpMethod: 'POST', headers: {}, body: JSON.stringify(message) })
    expect(response.statusCode).toBe(503)
    expect(table(fn._internal.APP_FEEDBACK).size).toBe(1)
    expect(table(fn._internal.APP_FEEDBACK).get('historic')).toEqual({ note: 'historical' })
    submit.mockRejectedValueOnce({ code: 'RATE_LIMITED' })
    expect(await fn.main(message)).toEqual({ ok: false, code: 'RATE_LIMITED' })
    submit.mockRejectedValueOnce({ code: 'STATE_CONFLICT' })
    expect(await fn.main(message)).toEqual({ ok: false, code: 'SUBMISSION_CONFLICT' })
  })

  it('rejects empty or oversized app feedback', async () => {
    createApp()
    const message = { action: 'feedback', deviceId, category: 'bug', note: '打不开' }
    expect(await fn.main({ ...message, note: '  ' })).toEqual({ ok: false, code: 'INVALID_FEEDBACK' })
    expect(await fn.main({ ...message, note: '长'.repeat(501) })).toEqual({ ok: false, code: 'INVALID_FEEDBACK' })
    expect(await fn.main({ ...message, category: 'spam' })).toEqual({ ok: false, code: 'INVALID_FEEDBACK' })
    expect(await fn.main({ ...message, platform: 'android' })).toEqual({ ok: false, code: 'INVALID_FEEDBACK' })
    expect(await fn.main({ ...message, contact: 'x'.repeat(101) })).toEqual({ ok: false, code: 'INVALID_FEEDBACK' })
  })

  it('accepts reports, signals and feedback posted to the HTTP trigger', async () => {
    const { table } = createApp()
    const post = (body: unknown, extra: Record<string, unknown> = {}) => fn.main({ httpMethod: 'POST', headers: {}, body: typeof body === 'string' ? body : JSON.stringify(body), ...extra })

    const res = await post({ ...report, note: '视频没了' })
    expect(res.statusCode).toBe(200)
    expect(JSON.parse(res.body)).toEqual({ ok: true })
    expect([...table(fn._internal.FEEDBACK).values()]).toHaveLength(1)

    const signal = Buffer.from(JSON.stringify({ action: 'signal', deviceId, type: 'empty', stuff: ['羊肉'] })).toString('base64')
    expect((await post(signal, { isBase64Encoded: true })).statusCode).toBe(200)
    expect([...table(fn._internal.SIGNALS).values()]).toHaveLength(1)

    expect((await post({ action: 'feedback', deviceId, category: 'other', note: '好用', platform: 'ios' })).statusCode).toBe(200)

    expect((await post({ ...report, kind: 'spam' })).statusCode).toBe(400)
    expect((await post('not json')).statusCode).toBe(400)
    expect((await post({ action: 'export', deviceId })).statusCode).toBe(400)
    expect((await post({ ...report, note: 'x'.repeat(5000) })).statusCode).toBe(400)

    const [quota] = [...table(fn._internal.QUOTA).values()]
    quota!.count = fn._internal.DAILY_QUOTA
    expect((await post(report)).statusCode).toBe(429)
  })

  it('does not export over POST, even with the token', async () => {
    createApp()
    process.env.COOK_FEEDBACK_EXPORT_TOKEN = 'secret-token'
    const res = await fn.main({ httpMethod: 'POST', headers: { 'x-cook-token': 'secret-token' }, body: '{}' })
    expect(res.statusCode).toBe(400)
  })

  it('refuses the export when no token is configured', async () => {
    createApp()
    expect((await fn.main({ httpMethod: 'GET', headers: { 'x-cook-token': '' } })).statusCode).toBe(401)
  })
})
