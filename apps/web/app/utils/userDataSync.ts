import type { UserData } from './userData'
import {
  createEmptyUserData,
  isSameUserData,
  mergeUserData,
  normalizeUserData,
  USER_DATA_VERSION,
} from './userData'

export interface UserDataDocument extends UserData {
  version: typeof USER_DATA_VERSION
  updatedAt: number
}

export interface UserDataRemote {
  /** Resolves null when the user has no document yet. */
  read: (uid: string) => Promise<unknown | null>
  write: (uid: string, document: UserDataDocument) => Promise<unknown | void>
}

export interface UserDataLocal {
  read: () => UserData
  write: (data: UserData) => void
  /** The account this device last synced with. */
  readLastUid: () => string
  writeLastUid: (uid: string) => void
}

/** Save the previous account before selecting another local library. */
export function activateUserDataAccount(uid: string, local: UserDataLocal, cache: Pick<Storage, 'getItem' | 'setItem'>): void {
  const previous = local.readLastUid()
  if (previous === uid)
    return
  const key = (owner: string) => `cook:account-library:${encodeURIComponent(owner)}`
  const saved = cache.getItem(key(uid))
  let next = createEmptyUserData()
  if (saved !== null) {
    try {
      const value = normalizeUserData(JSON.parse(saved))
      if (!value)
        throw new Error('unsupported cache')
      next = value
    }
    catch (cause) {
      throw new UserDataSyncError('invalid_remote', { cause })
    }
  }
  // setItem is atomic: a quota failure leaves the active library untouched.
  cache.setItem(key(previous), JSON.stringify({ version: USER_DATA_VERSION, ...local.read() }))
  local.write(next)
  local.writeLastUid(uid)
}

export class UserDataSyncError extends Error {
  constructor(public readonly code: 'invalid_remote' | 'remote_failed' | 'cancelled' | 'membership_required' | 'login_required' | 'scope_failed', options?: { cause?: unknown }) {
    super(code === 'invalid_remote' ? '云端数据格式无法识别' : code === 'cancelled' ? '同步会话已改变' : code === 'membership_required' ? '跨平台同步需要有效的云乐坊会员，本机记录已保留' : code === 'login_required' ? '请重新登录后同步，本机记录已保留' : code === 'scope_failed' ? '账号记录切换失败，原数据已保留，请恢复浏览器存储后重试' : '云端同步失败', options)
    this.name = 'UserDataSyncError'
  }
}

/**
 * One round of read → merge → write.
 *
 * - Same account (or a device that never synced): local and cloud are merged.
 * - Another account synced here last: its data is already in its own cloud
 *   document, so the cloud replaces local instead of leaking into this account.
 * - An unrecognised cloud document is never overwritten.
 */
export async function syncUserData(
  uid: string,
  local: UserDataLocal,
  remote: UserDataRemote,
  now = Date.now(),
  isCurrent: () => boolean = () => true,
): Promise<UserData> {
  const checkSession = () => {
    if (!isCurrent())
      throw new UserDataSyncError('cancelled')
  }
  checkSession()
  let raw: unknown | null
  try {
    raw = await remote.read(uid)
  }
  catch (error) {
    checkSession()
    if (error instanceof UserDataSyncError)
      throw error
    throw new UserDataSyncError('remote_failed', { cause: error })
  }

  checkSession()
  const cloud = raw === null ? createEmptyUserData() : normalizeUserData(raw)
  if (!cloud)
    throw new UserDataSyncError('invalid_remote')

  const lastUid = local.readLastUid()
  const current = local.read()
  const base = lastUid && lastUid !== uid ? createEmptyUserData() : current
  let merged = mergeUserData(base, cloud, now)

  if (!isSameUserData(merged, current))
    local.write(merged)
  local.writeLastUid(uid)

  if (raw === null || !isSameUserData(merged, cloud)) {
    try {
      const accepted = await remote.write(uid, { version: USER_DATA_VERSION, ...merged, updatedAt: now })
      checkSession()
      if (accepted !== undefined) {
        const final = normalizeUserData(accepted)
        if (!final)
          throw new UserDataSyncError('invalid_remote')
        // Include changes made locally while the request was in flight.
        merged = mergeUserData(local.read(), final, now)
        if (!isSameUserData(merged, local.read()))
          local.write(merged)
      }
    }
    catch (error) {
      checkSession()
      if (error instanceof UserDataSyncError)
        throw error
      throw new UserDataSyncError('remote_failed', { cause: error })
    }
  }

  return merged
}

export interface UserDataSyncScheduler {
  /** Run a sync after `delay` ms; repeated requests are coalesced. */
  request: (delay?: number) => void
  stop: () => void
}

export interface UserDataSyncSchedulerOptions {
  run: () => Promise<void>
  onError?: (error: unknown) => void
  /** Defaults to retrying every failure. */
  shouldRetry?: (error: unknown) => boolean
  maxRetryDelay?: number
}

/**
 * Serialises sync rounds: at most one runs at a time, a request made while one
 * is running triggers another round afterwards, and failures retry with
 * exponential backoff until a round succeeds or `stop()` is called.
 */
export function createUserDataSyncScheduler(options: UserDataSyncSchedulerOptions): UserDataSyncScheduler {
  const maxRetryDelay = options.maxRetryDelay ?? 30_000
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false
  let pending = false
  let stopped = false
  let retry = 0

  function request(delay = 0): void {
    stopped = false
    if (running) {
      pending = true
      return
    }
    clearTimeout(timer)
    timer = setTimeout(flush, delay)
  }

  async function flush(): Promise<void> {
    timer = undefined
    if (stopped || running)
      return
    running = true
    pending = false
    let retryDelay: number | undefined
    try {
      await options.run()
      retry = 0
    }
    catch (error) {
      options.onError?.(error)
      if (options.shouldRetry?.(error) ?? true) {
        retryDelay = Math.min(maxRetryDelay, 1000 * 2 ** retry)
        retry += 1
      }
    }
    finally {
      running = false
    }
    if (stopped)
      return
    if (pending)
      request()
    else if (retryDelay !== undefined)
      request(retryDelay)
  }

  return {
    request,
    stop() {
      stopped = true
      pending = false
      retry = 0
      clearTimeout(timer)
      timer = undefined
    },
  }
}
