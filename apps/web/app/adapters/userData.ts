import type { CloudbaseFunctionCaller } from './yunlefunAuth'
import type { UserDataRemote } from '~/utils/userDataSync'

export const USER_DATA_FUNCTION = 'cook-user-data'

interface FunctionResult {
  code?: string
  document?: unknown
  ok?: boolean
}

/**
 * Talks to the `cook-user-data` cloud function, which stores one document per
 * account. The function takes the account from the verified session, so the
 * client never names a uid.
 */
export function createCloudbaseUserDataRemote(callFunction: CloudbaseFunctionCaller): UserDataRemote {
  async function call(data: Record<string, unknown>): Promise<FunctionResult> {
    const result = await callFunction(USER_DATA_FUNCTION, data) as FunctionResult | null
    if (!result || result.ok !== true)
      throw new Error(`${USER_DATA_FUNCTION} failed: ${result?.code ?? 'unknown'}`)
    return result
  }

  return {
    read: async () => (await call({ action: 'get' })).document ?? null,
    write: async (_uid, document) => {
      await call({ action: 'set', document: { ...document } })
    },
  }
}
