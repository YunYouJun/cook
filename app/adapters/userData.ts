import type { CloudbaseDatabase } from './yunlefunAuth'
import type { UserDataRemote } from '~/utils/userDataSync'
import { USER_DATA_COLLECTION } from '~/utils/userDataSync'

/**
 * One document per account in the shared CloudBase env, `_id = uid`.
 * The collection's CUSTOM security rule only lets the owner read and write it.
 */
export function createCloudbaseUserDataRemote(
  getDatabase: () => Promise<CloudbaseDatabase>,
): UserDataRemote {
  async function docRef(uid: string) {
    const database = await getDatabase()
    return database.collection(USER_DATA_COLLECTION).doc(uid)
  }

  return {
    read: async (uid) => {
      const result = await (await docRef(uid)).get()
      if (result.code)
        throw new Error(`CloudBase read failed: ${result.code}`)
      return result.data?.[0] ?? null
    },
    write: async (uid, document) => {
      const result = await (await docRef(uid)).set({ ...document })
      if (result?.code)
        throw new Error(`CloudBase write failed: ${result.code}`)
      // A rule rejection can surface as a write that touched nothing.
      if (result && result.updated === 0 && !result.upsertedId)
        throw new Error('CloudBase write was not applied')
    },
  }
}
