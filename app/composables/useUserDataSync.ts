import type { UserDataLocal, UserDataSyncScheduler } from '~/utils/userDataSync'
import { createCloudbaseUserDataRemote } from '~/adapters/userData'
import { favoriteEntries } from '~/composables/store/favorite'
import { recipeHistories } from '~/composables/store/history'
import { localUserData } from '~/composables/store/userData'
import { userDataMeta } from '~/composables/store/userDataMeta'
import {
  createUserDataSyncScheduler,
  syncUserData,
  UserDataSyncError,
} from '~/utils/userDataSync'

export type UserDataSyncStatus = 'idle' | 'syncing' | 'synced' | 'error'

interface UserDataSyncState {
  errorMessage: string
  lastSyncedAt: number
  status: UserDataSyncStatus
}

/** Wait this long after a local change before pushing it. */
const LOCAL_CHANGE_DELAY = 2000

let scheduler: UserDataSyncScheduler | undefined

/**
 * Keeps favorites and history in sync with the signed-in 云乐坊 account.
 * Logged out, nothing runs and local data behaves as before.
 */
export function useUserDataSync() {
  const state = useState<UserDataSyncState>('cook:user-data-sync', () => ({
    errorMessage: '',
    lastSyncedAt: 0,
    status: 'idle',
  }))

  function install(): void {
    if (!import.meta.client || scheduler)
      return

    const auth = useYunlefunAuth()
    const uid = computed(() => auth.isAuthenticated.value ? auth.account.value?.uid ?? '' : '')
    const remote = createCloudbaseUserDataRemote(auth.callFunction)

    // Local writes made by a sync round must not schedule another round.
    let applyingRemote = false
    const local: UserDataLocal = {
      ...localUserData,
      write: (data) => {
        applyingRemote = true
        try {
          localUserData.write(data)
        }
        finally {
          applyingRemote = false
        }
      },
    }

    const activeScheduler = createUserDataSyncScheduler({
      run: async () => {
        const current = uid.value
        if (!current)
          return
        state.value = { ...state.value, errorMessage: '', status: 'syncing' }
        await syncUserData(current, local, remote)
        if (uid.value === current)
          state.value = { errorMessage: '', lastSyncedAt: Date.now(), status: 'synced' }
      },
      onError: (error) => {
        if (!uid.value)
          return
        state.value = {
          ...state.value,
          errorMessage: error instanceof UserDataSyncError ? error.message : '云端同步失败',
          status: 'error',
        }
      },
      shouldRetry: error => !(error instanceof UserDataSyncError && error.code === 'invalid_remote'),
    })
    scheduler = activeScheduler

    watch(uid, (current) => {
      if (current) {
        activeScheduler.request()
        return
      }
      activeScheduler.stop()
      state.value = { errorMessage: '', lastSyncedAt: 0, status: 'idle' }
    }, { immediate: true })

    watch(
      () => [
        favoriteEntries.value,
        recipeHistories.value,
        userDataMeta.value.favoriteRemovals,
        userDataMeta.value.historyRemovals,
        userDataMeta.value.historyClearedAt,
      ],
      () => {
        if (!applyingRemote && uid.value)
          activeScheduler.request(LOCAL_CHANGE_DELAY)
      },
      { deep: true, flush: 'sync' },
    )

    // Pull changes made on other devices when coming back to the app.
    const pull = () => {
      if (uid.value && document.visibilityState === 'visible')
        activeScheduler.request()
    }
    document.addEventListener('visibilitychange', pull)
    window.addEventListener('online', pull)
  }

  function syncNow(): void {
    scheduler?.request()
  }

  return {
    errorMessage: computed(() => state.value.errorMessage),
    install,
    lastSyncedAt: computed(() => state.value.lastSyncedAt),
    status: computed(() => state.value.status),
    syncNow,
  }
}
