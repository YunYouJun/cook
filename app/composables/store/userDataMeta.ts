import type { FavoriteRemoval, HistoryRemoval } from '~/utils/userData'
import { useStorage } from '@vueuse/core'
import { namespace } from '~/constants'

/**
 * Sync bookkeeping kept next to `cook:favorites` and `cook:history`, which stay
 * in their original format. Deletions are recorded even when logged out so they
 * can be applied to the cloud after the next login.
 */
export interface UserDataMeta {
  favoriteRemovals: FavoriteRemoval[]
  historyRemovals: HistoryRemoval[]
  historyClearedAt: number
  /** The account this device last synced with. */
  lastUid: string
}

export const userDataMeta = useStorage<UserDataMeta>(`${namespace}:sync`, {
  favoriteRemovals: [],
  historyRemovals: [],
  historyClearedAt: 0,
  lastUid: '',
}, undefined, { mergeDefaults: true })
