import type { UserData } from './userData'
import {
  createEmptyUserData,
  isSameUserData,
  mergeUserData,
  normalizeUserData,
  USER_DATA_VERSION,
} from './userData'

export const USER_DATA_COLLECTION = 'cook_user_data'

export interface UserDataDocument extends UserData {
  version: typeof USER_DATA_VERSION
  updatedAt: number
}

export interface UserDataRemote {
  /** Resolves null when the user has no document yet. */
  read: (uid: string) => Promise<unknown | null>
  write: (uid: string, document: UserDataDocument) => Promise<void>
}

export interface UserDataLocal {
  read: () => UserData
  write: (data: UserData) => void
  /** The account this device last synced with. */
  readLastUid: () => string
  writeLastUid: (uid: string) => void
}

export class UserDataSyncError extends Error {
  constructor(public readonly code: 'invalid_remote' | 'remote_failed', options?: { cause?: unknown }) {
    super(code === 'invalid_remote' ? '云端数据格式无法识别' : '云端同步失败', options)
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
): Promise<UserData> {
  let raw: unknown | null
  try {
    raw = await remote.read(uid)
  }
  catch (error) {
    throw new UserDataSyncError('remote_failed', { cause: error })
  }

  const cloud = raw === null ? createEmptyUserData() : normalizeUserData(raw)
  if (!cloud)
    throw new UserDataSyncError('invalid_remote')

  const lastUid = local.readLastUid()
  const current = local.read()
  const base = lastUid && lastUid !== uid ? createEmptyUserData() : current
  const merged = mergeUserData(base, cloud, now)

  if (!isSameUserData(merged, current))
    local.write(merged)
  local.writeLastUid(uid)

  if (raw === null || !isSameUserData(merged, cloud)) {
    try {
      await remote.write(uid, { version: USER_DATA_VERSION, ...merged, updatedAt: now })
    }
    catch (error) {
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
