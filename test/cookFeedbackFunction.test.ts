import { Buffer } from 'node:buffer'
import { createRequire } from 'node:module'
import { beforeEach, describe, expect, it } from 'vitest'

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

type Doc = Record<string, any>

/** In-memory stand-in for the parts of the CloudBase database the function uses. */
function createApp(uid = '') {
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
  fn._internal.setAppForTest({
    auth: () => ({ getUserInfo: () => ({ uid }) }),
    database: () => ({ collection, command }),
  })
  return { table }
}

const deviceId = 'device-0123456789abcdef'
const report = { action: 'report', deviceId, recipeId: 3, recipeName: '番茄炒蛋', kind: 'video' }

describe('cook-feedback function', () => {
  beforeEach(() => {
    delete process.env.COOK_FEEDBACK_EXPORT_TOKEN
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

  it('stores general app feedback with an optional contact', async () => {
    const { table } = createApp()
    const message = { action: 'feedback', deviceId, category: 'idea', note: ' 希望能按季节推荐 ', contact: 'wx: cook', platform: 'ios', version: '2.0.0' }
    expect(await fn.main(message)).toEqual({ ok: true })
    expect(await fn.main(message)).toEqual({ ok: true })
    const rows = [...table(fn._internal.APP_FEEDBACK).values()]
    expect(rows).toHaveLength(1)
    expect(rows[0]).toMatchObject({ category: 'idea', note: '希望能按季节推荐', contact: 'wx: cook', platform: 'ios', version: '2.0.0', uid: '' })
    expect(await fn.main({ action: 'feedback', deviceId, category: 'bug', note: '闪退' })).toEqual({ ok: true })
    expect([...table(fn._internal.APP_FEEDBACK).values()].find(r => r.note === '闪退')).toMatchObject({ platform: 'web', contact: '' })
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
