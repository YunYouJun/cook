import type { CloudbaseFunctionCaller } from './yunlefunAuth'
import type { UserDataRemote } from '~/utils/userDataSync'
import { UserDataSyncError } from '~/utils/userDataSync'

export const USER_DATA_FUNCTION = 'cook-user-data'

interface FunctionResult {
  code?: string
  document?: unknown
  ok?: boolean
  protocolVersion?: number
}

/**
 * Talks to the `cook-user-data` cloud function, which stores one document per
 * account. The function takes the account from the verified session, so the
 * client never names a uid.
 */
export function createCloudbaseUserDataRemote(callFunction: CloudbaseFunctionCaller): UserDataRemote {
  async function call(data: Record<string, unknown>): Promise<FunctionResult> {
    const result = await callFunction(USER_DATA_FUNCTION, data) as FunctionResult | null
    if (result?.code === 'MEMBERSHIP_REQUIRED')
      throw new UserDataSyncError('membership_required')
    if (result?.code === 'LOGIN_REQUIRED')
      throw new UserDataSyncError('login_required')
    if (result?.code === 'SESSION_CHANGED')
      throw new UserDataSyncError('cancelled')
    if (['INVALID_REMOTE', 'INVALID_DOCUMENT', 'DOCUMENT_LIMIT', 'UNKNOWN_ACTION'].includes(result?.code ?? ''))
      throw new UserDataSyncError('invalid_remote')
    if (!result || result.ok !== true)
      throw new Error(`${USER_DATA_FUNCTION} failed: ${result?.code ?? 'unknown'}`)
    return result
  }

  async function ownerDigest(uid: string): Promise<string> {
    const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(uid))
    return Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('')
  }

  return {
    read: async (uid) => {
      const owner = await ownerDigest(uid)
      if ((await call({ action: 'capabilities', ownerDigest: owner })).protocolVersion !== 2)
        throw new UserDataSyncError('invalid_remote')
      return (await call({ action: 'get', ownerDigest: owner })).document ?? null
    },
    write: async (uid, document) => {
      const result = await call({ action: 'sync', ownerDigest: await ownerDigest(uid), document: { ...document } })
      if (result.protocolVersion !== 2 || !result.document)
        throw new UserDataSyncError('invalid_remote')
      return result.document
    },
  }
}
