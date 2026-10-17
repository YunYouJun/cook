import type { UserData, UserDataExportFile } from '~/utils/userData'
import type { UserDataLocal } from '~/utils/userDataSync'
import {
  createEmptyUserData,
  createUserDataExport,
  mergeUserData,
  parseUserDataExport,
} from '~/utils/userData'
import { favoriteEntries, setFavoriteEntries } from './favorite'
import { recipeHistories } from './history'
import { userDataMeta } from './userDataMeta'

export function readLocalUserData(): UserData {
  const meta = userDataMeta.value
  return {
    favorites: favoriteEntries.value,
    favoriteRemovals: meta.favoriteRemovals ?? [],
    histories: recipeHistories.value,
    historyRemovals: meta.historyRemovals ?? [],
    historyClearedAt: meta.historyClearedAt ?? 0,
  }
}

export function writeLocalUserData(data: UserData): void {
  setFavoriteEntries(data.favorites)
  recipeHistories.value = data.histories
  userDataMeta.value = {
    ...userDataMeta.value,
    favoriteRemovals: data.favoriteRemovals,
    historyRemovals: data.historyRemovals,
    historyClearedAt: data.historyClearedAt,
  }
}

export const localUserData: UserDataLocal = {
  read: readLocalUserData,
  write: writeLocalUserData,
  readLastUid: () => userDataMeta.value.lastUid ?? '',
  writeLastUid: (uid) => {
    if (userDataMeta.value.lastUid !== uid)
      userDataMeta.value = { ...userDataMeta.value, lastUid: uid }
  },
}

export function exportLocalUserData(now = Date.now()): UserDataExportFile {
  return createUserDataExport(mergeUserData(readLocalUserData(), createEmptyUserData(), now), now)
}

/**
 * Merge an exported file into local data, using the same rules as cloud sync.
 * Returns null when the text is not a Cook export.
 */
export function importLocalUserData(text: string, now = Date.now()): { favorites: number, histories: number } | null {
  const imported = parseUserDataExport(text)
  if (!imported)
    return null
  const merged = mergeUserData(readLocalUserData(), imported, now)
  writeLocalUserData(merged)
  return { favorites: merged.favorites.length, histories: merged.histories.length }
}
