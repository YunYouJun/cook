import { createRequire } from 'node:module'
import { beforeEach, describe, expect, it, vi } from 'vitest'

interface FunctionModule {
  main: (event?: Record<string, unknown>) => Promise<Record<string, unknown>>
  _internal: {
    COLLECTION: string
    LIST_LIMITS: Record<string, number>
    MAX_DOCUMENT_BYTES: number
    readDocument: (value: unknown) => Record<string, unknown> | null
    setAppForTest: (app: unknown) => void
  }
}

const require = createRequire(import.meta.url)
const fn = require('../cloudfunctions/cook-user-data/index.js') as FunctionModule

function createApp(userInfo: Record<string, unknown> | (() => never)) {
  const docs = new Map<string, Record<string, unknown>>()
  const doc = vi.fn((id: string) => ({
    get: async () => ({ data: docs.has(id) ? [{ _id: id, _openid: 'x', ...docs.get(id) }] : [] }),
    set: async (data: Record<string, unknown>) => {
      docs.set(id, data)
      return { updated: 1 }
    },
  }))
  const collection = vi.fn(() => ({ doc }))
  const app = {
    auth: () => ({ getUserInfo: typeof userInfo === 'function' ? userInfo : () => userInfo }),
    database: () => ({ collection }),
  }
  fn._internal.setAppForTest(app)
  return { collection, doc, docs }
}

const document = {
  version: 1,
  favorites: [{ id: 1, time: 1 }],
  favoriteRemovals: [],
  histories: [{ recipe: { name: 'A', stuff: [], tools: [] }, time: 2 }],
  historyRemovals: [],
  historyClearedAt: 0,
}

describe('cook-user-data function', () => {
  beforeEach(() => {
    vi.useRealTimers()
  })

  it('rejects callers without a real account', async () => {
    for (const info of [{}, { uid: '' }, { uid: 'anon' }, { uid: 'u1', isAnonymous: true }]) {
      const { doc } = createApp(info)
      await expect(fn.main({ action: 'get' })).resolves.toEqual({ ok: false, code: 'LOGIN_REQUIRED' })
      expect(doc).not.toHaveBeenCalled()
    }
    createApp(() => {
      throw new Error('no context')
    })
    await expect(fn.main({ action: 'get' })).resolves.toEqual({ ok: false, code: 'LOGIN_REQUIRED' })
  })

  it('always uses the caller uid as the document id', async () => {
    const { collection, doc, docs } = createApp({ uid: 'u1' })

    const result = await fn.main({ action: 'set', document, uid: 'victim', _id: 'victim' })

    expect(result).toMatchObject({ ok: true })
    expect(collection).toHaveBeenCalledWith('cook_user_data')
    expect(doc.mock.calls.map(call => call[0])).toEqual(['u1'])
    expect(docs.has('victim')).toBe(false)
    expect(docs.get('u1')).toMatchObject({ version: 1, favorites: [{ id: 1, time: 1 }] })
  })

  it('returns the caller document without CloudBase metadata', async () => {
    createApp({ uid: 'u1' })
    await fn.main({ action: 'set', document })

    const result = await fn.main({ action: 'get' })

    expect(result.ok).toBe(true)
    expect(result.document).toMatchObject({ version: 1, favorites: [{ id: 1, time: 1 }] })
    expect(result.document).not.toHaveProperty('_id')
    expect(result.document).not.toHaveProperty('_openid')
  })

  it('returns null for an account without a document', async () => {
    createApp({ uid: 'u2' })
    await expect(fn.main({ action: 'get' })).resolves.toEqual({ ok: true, document: null })
  })

  it('rejects malformed or oversized documents', async () => {
    const { docs } = createApp({ uid: 'u1' })
    const bad = [
      null,
      { ...document, version: 2 },
      { ...document, favorites: 'x' },
      { ...document, favorites: [1] },
      { ...document, histories: Array.from({ length: fn._internal.LIST_LIMITS.histories! + 1 }, () => ({})) },
      { ...document, historyClearedAt: -1 },
      { ...document, favorites: [{ id: 1, note: 'x'.repeat(fn._internal.MAX_DOCUMENT_BYTES) }] },
    ]
    for (const input of bad)
      await expect(fn.main({ action: 'set', document: input })).resolves.toEqual({ ok: false, code: 'INVALID_DOCUMENT' })
    expect(docs.size).toBe(0)
  })

  it('drops unknown top-level fields', () => {
    expect(fn._internal.readDocument({ ...document, _id: 'victim', admin: true })).toEqual({ ...document })
  })

  it('rejects unknown actions and hides internal errors', async () => {
    createApp({ uid: 'u1' })
    await expect(fn.main({ action: 'drop' })).resolves.toEqual({ ok: false, code: 'UNKNOWN_ACTION' })

    fn._internal.setAppForTest({
      auth: () => ({ getUserInfo: () => ({ uid: 'u1' }) }),
      database: () => {
        throw new Error('db down')
      },
    })
    const error = vi.spyOn(console, 'error').mockImplementation(() => {})
    await expect(fn.main({ action: 'get' })).resolves.toEqual({ ok: false, code: 'INTERNAL_ERROR' })
    error.mockRestore()
  })
})
