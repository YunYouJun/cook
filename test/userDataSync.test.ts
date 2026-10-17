import type { UserData } from '../app/utils/userData'
import type { UserDataDocument, UserDataLocal, UserDataRemote } from '../app/utils/userDataSync'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createEmptyUserData } from '../app/utils/userData'
import { createUserDataSyncScheduler, syncUserData, USER_DATA_COLLECTION, UserDataSyncError } from '../app/utils/userDataSync'

const NOW = 1_800_000_000_000

function createLocal(initial: Partial<UserData> = {}, lastUid = '') {
  const state = { data: { ...createEmptyUserData(), ...initial }, lastUid, writes: 0 }
  const local: UserDataLocal = {
    read: () => state.data,
    write: (data) => {
      state.data = data
      state.writes += 1
    },
    readLastUid: () => state.lastUid,
    writeLastUid: (uid) => {
      state.lastUid = uid
    },
  }
  return { local, state }
}

function createRemote(initial: Record<string, unknown> = {}) {
  const docs = new Map<string, unknown>(Object.entries(initial))
  const write = vi.fn(async (uid: string, document: UserDataDocument) => {
    docs.set(uid, JSON.parse(JSON.stringify(document)))
  })
  const remote: UserDataRemote = {
    read: async uid => docs.get(uid) ?? null,
    write,
  }
  return { docs, remote, write }
}

describe('syncUserData', () => {
  it('merges local data into a new cloud document on first sync', async () => {
    const { local, state } = createLocal({ favorites: [{ id: 1, time: NOW - 1 }] })
    const { docs, remote } = createRemote()

    await syncUserData('u1', local, remote, NOW)

    expect(docs.get('u1')).toMatchObject({ version: 1, updatedAt: NOW, favorites: [{ id: 1, time: NOW - 1 }] })
    expect(state.lastUid).toBe('u1')
    expect(state.writes).toBe(0)
  })

  it('merges cloud and local for the same account', async () => {
    const { local, state } = createLocal({ favorites: [{ id: 1, time: NOW - 2 }] }, 'u1')
    const { docs, remote } = createRemote({ u1: { version: 1, favorites: [{ id: 2, time: NOW - 1 }] } })

    await syncUserData('u1', local, remote, NOW)

    expect(state.data.favorites.map(f => f.id)).toEqual([1, 2])
    expect((docs.get('u1') as UserData).favorites.map(f => f.id)).toEqual([1, 2])
  })

  it('does not write anything when both sides already agree', async () => {
    const value = { favorites: [{ id: 1, time: NOW - 1 }] }
    const { local, state } = createLocal(value, 'u1')
    const { remote, write } = createRemote({ u1: { version: 1, ...value } })

    await syncUserData('u1', local, remote, NOW)

    expect(write).not.toHaveBeenCalled()
    expect(state.writes).toBe(0)
  })

  it('replaces local data instead of merging after an account switch', async () => {
    const { local, state } = createLocal({ favorites: [{ id: 1, time: NOW - 2 }] }, 'u1')
    const { docs, remote, write } = createRemote({ u2: { version: 1, favorites: [{ id: 2, time: NOW - 1 }] } })

    await syncUserData('u2', local, remote, NOW)

    expect(state.data.favorites.map(f => f.id)).toEqual([2])
    expect(state.lastUid).toBe('u2')
    expect(write).not.toHaveBeenCalled()
    expect(docs.has('u1')).toBe(false)
  })

  it('never overwrites an unrecognised cloud document', async () => {
    const { local, state } = createLocal({ favorites: [{ id: 1, time: NOW - 1 }] })
    const { remote, write } = createRemote({ u1: { version: 99 } })

    await expect(syncUserData('u1', local, remote, NOW)).rejects.toMatchObject({ code: 'invalid_remote' })
    expect(write).not.toHaveBeenCalled()
    expect(state.lastUid).toBe('')
  })

  it('keeps local data when the cloud is unreachable', async () => {
    const { local, state } = createLocal({ favorites: [{ id: 1, time: NOW - 1 }] })
    const remote: UserDataRemote = {
      read: async () => {
        throw new Error('offline')
      },
      write: vi.fn(),
    }

    const error = await syncUserData('u1', local, remote, NOW).catch(e => e)
    expect(error).toBeInstanceOf(UserDataSyncError)
    expect(error.code).toBe('remote_failed')
    expect(state.data.favorites).toEqual([{ id: 1, time: NOW - 1 }])
  })
})

describe('createUserDataSyncScheduler', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('coalesces requests made before the delay elapses', async () => {
    const run = vi.fn(async () => {})
    const scheduler = createUserDataSyncScheduler({ run })

    scheduler.request(2000)
    scheduler.request(2000)
    await vi.advanceTimersByTimeAsync(1999)
    expect(run).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(run).toHaveBeenCalledTimes(1)
  })

  it('runs again after a request made during a run', async () => {
    let finish!: () => void
    const run = vi.fn(() => new Promise<void>((resolve) => {
      finish = resolve
    }))
    const scheduler = createUserDataSyncScheduler({ run })

    scheduler.request()
    await vi.advanceTimersByTimeAsync(0)
    scheduler.request(2000)
    finish()
    await vi.advanceTimersByTimeAsync(0)
    expect(run).toHaveBeenCalledTimes(2)
  })

  it('retries failures with exponential backoff', async () => {
    const onError = vi.fn()
    const run = vi.fn()
      .mockRejectedValueOnce(new Error('a'))
      .mockRejectedValueOnce(new Error('b'))
      .mockResolvedValue(undefined)
    const scheduler = createUserDataSyncScheduler({ run, onError })

    scheduler.request()
    await vi.advanceTimersByTimeAsync(0)
    expect(run).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1000)
    expect(run).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1999)
    expect(run).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(run).toHaveBeenCalledTimes(3)
    await vi.advanceTimersByTimeAsync(60_000)
    expect(run).toHaveBeenCalledTimes(3)
    expect(onError).toHaveBeenCalledTimes(2)
  })

  it('stops retrying after stop()', async () => {
    const run = vi.fn().mockRejectedValue(new Error('offline'))
    const scheduler = createUserDataSyncScheduler({ run })

    scheduler.request()
    await vi.advanceTimersByTimeAsync(0)
    scheduler.stop()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(run).toHaveBeenCalledTimes(1)
  })
})

describe('cook_user_data security rule', () => {
  it('targets the sync collection and only lets the owner touch their document', async () => {
    const { default: rule } = await import('../scripts/cloudbase-rules/cook_user_data.json')
    expect(rule.resourceId).toBe(USER_DATA_COLLECTION)
    expect(rule.permission).toBe('CUSTOM')
    const securityRule = JSON.parse(rule.securityRule) as Record<string, string>
    expect(Object.keys(securityRule).sort()).toEqual(['create', 'delete', 'read', 'update'])
    // create rules cannot read doc.*: the document does not exist yet.
    expect(securityRule.create).not.toContain('doc.')
    expect(securityRule.create).toContain('auth.loginType != \'ANONYMOUS\'')
    for (const action of ['read', 'update', 'delete'])
      expect(securityRule[action]).toContain('doc._id == auth.uid')
  })
})
