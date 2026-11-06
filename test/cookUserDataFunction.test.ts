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

function createApp(userInfo: Record<string, unknown> | (() => never), expiry = Date.now() + 86_400_000) {
  const docs = new Map<string, Record<string, unknown>>()
  const doc = vi.fn((id: string) => ({
    get: async () => ({ data: docs.has(id) ? [{ _id: id, _openid: 'x', ...docs.get(id) }] : [] }),
    set: async (data: Record<string, unknown>) => {
      docs.set(id, data)
      return { updated: 1 }
    },
  }))
  const collection = vi.fn((name: string) => name === 'user_memberships'
    ? {
        doc: () => ({ get: async () => ({ data: [{ expireAt: expiry }] }) }),
      }
    : { doc })
  let previous = Promise.resolve()
  const runTransaction = vi.fn((run: (transaction: unknown) => Promise<unknown>) => {
    const result = previous.then(() => run({ collection }))
    previous = result.then(() => {}, () => {})
    return result.then(result => ({ result }))
  })
  const app = {
    auth: () => ({ getUserInfo: typeof userInfo === 'function' ? userInfo : () => userInfo }),
    database: () => ({ collection, runTransaction }),
  }
  fn._internal.setAppForTest(app)
  return { collection, doc, docs, runTransaction }
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

  it('rejects a changed SDK session before any account or data lookup', async () => {
    const { collection } = createApp({ uid: 'u2' })
    await expect(fn.main({ action: 'sync', ownerDigest: '0'.repeat(64), document })).resolves.toEqual({ ok: false, code: 'SESSION_CHANGED' })
    expect(collection).not.toHaveBeenCalled()
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

  it('merges stale snapshots from old and new clients instead of replacing accepted changes', async () => {
    const { docs } = createApp({ uid: 'u1' })
    const first = { ...document, favorites: [{ id: 1, time: Date.now() }] }
    const second = { ...document, favorites: [{ id: 2, time: Date.now() + 1 }] }
    await fn.main({ action: 'sync', document: first })
    await fn.main({ action: 'set', document: second })
    expect(docs.get('u1')?.favorites).toEqual([...first.favorites, ...second.favorites])
    await expect(fn.main({ action: 'capabilities' })).resolves.toEqual({ ok: true, protocolVersion: 2 })
  })

  it('rejects expired membership for all legacy and new actions without touching user data', async () => {
    const { doc } = createApp({ uid: 'u1' }, Date.now() - 1)
    for (const action of ['get', 'set', 'sync', 'capabilities'])
      await expect(fn.main({ action, document, membership: { isActive: true } })).resolves.toEqual({ ok: false, code: 'MEMBERSHIP_REQUIRED' })
    expect(doc).not.toHaveBeenCalled()
  })

  it('does not overwrite an unsupported existing version', async () => {
    const { docs } = createApp({ uid: 'u1' })
    docs.set('u1', { version: 99, favorites: [{ id: 7, time: 1 }] })
    await expect(fn.main({ action: 'sync', document })).resolves.toEqual({ ok: false, code: 'INVALID_REMOTE' })
    expect(docs.get('u1')?.version).toBe(99)
  })

  it('serializes concurrent snapshots and does not resurrect a cancelled favorite', async () => {
    const { docs, runTransaction } = createApp({ uid: 'u1' })
    const now = Date.now()
    await Promise.all([
      fn.main({ action: 'sync', document: { ...document, favorites: [{ id: 1, time: now }] } }),
      fn.main({ action: 'set', document: { ...document, favorites: [{ id: 2, time: now + 1 }] } }),
    ])
    expect(runTransaction).toHaveBeenCalledTimes(2)
    expect(docs.get('u1')?.favorites).toEqual([{ id: 1, time: now }, { id: 2, time: now + 1 }])
    await fn.main({ action: 'sync', document: { ...document, favorites: [], favoriteRemovals: [{ id: 1, time: now + 2 }] } })
    await fn.main({ action: 'set', document: { ...document, favorites: [{ id: 1, time: now }] } })
    expect(docs.get('u1')?.favorites).toEqual([{ id: 2, time: now + 1 }])
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
