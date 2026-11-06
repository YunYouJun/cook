import type { UserDataLocal, UserDataSyncScheduler } from '~/utils/userDataSync'
import { createCloudbaseUserDataRemote } from '~/adapters/userData'
import { favoriteEntries } from '~/composables/store/favorite'
import { recipeHistories } from '~/composables/store/history'
import { localUserData } from '~/composables/store/userData'
import { userDataMeta } from '~/composables/store/userDataMeta'
import {
  activateUserDataAccount,
  createUserDataSyncScheduler,
  syncUserData,
  UserDataSyncError,
} from '~/utils/userDataSync'

export type UserDataSyncStatus = 'idle' | 'syncing' | 'synced' | 'error'

interface UserDataSyncState {
  errorMessage: string
  lastSyncedAt: number
  status: UserDataSyncStatus
  scopeBlocked: boolean
  scopeRevision: number
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
    scopeBlocked: false,
    scopeRevision: 0,
  }))

  function install(): void {
    if (typeof window === 'undefined' || scheduler)
      return

    const auth = useYunlefunAuth()
    const uid = computed(() => auth.isAuthenticated.value ? auth.account.value?.uid ?? '' : '')
    let sessionGeneration = 0
    let scopeReady = true

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

    function selectLocalScope(current: string): void {
      scopeReady = false
      applyingRemote = true
      try {
        activateUserDataAccount(current, local, window.localStorage)
        scopeReady = true
        state.value = { ...state.value, scopeBlocked: false }
      }
      catch (cause) {
        state.value = { ...state.value, scopeBlocked: true, errorMessage: '账号记录切换失败，原数据已保留，请恢复浏览器存储后重试', lastSyncedAt: 0, status: 'error' }
        throw new UserDataSyncError('scope_failed', { cause })
      }
      finally { applyingRemote = false }
    }

    const activeScheduler = createUserDataSyncScheduler({
      run: async () => {
        const current = uid.value
        const generation = sessionGeneration
        const isCurrent = () => uid.value === current && sessionGeneration === generation
        if (!scopeReady)
          selectLocalScope(current)
        if (!current) {
          state.value = { ...state.value, errorMessage: '', status: 'idle' }
          return
        }
        state.value = { ...state.value, errorMessage: '', status: 'syncing' }
        const remote = createCloudbaseUserDataRemote((name, data) => {
          if (!isCurrent())
            throw new UserDataSyncError('cancelled')
          return auth.callFunction(name, data)
        })
        await syncUserData(current, local, remote, Date.now(), isCurrent)
        if (uid.value === current)
          state.value = { ...state.value, errorMessage: '', lastSyncedAt: Date.now(), status: 'synced' }
      },
      onError: (error) => {
        if (!uid.value || (error instanceof UserDataSyncError && error.code === 'cancelled'))
          return
        state.value = {
          ...state.value,
          errorMessage: error instanceof UserDataSyncError ? error.message : '云端同步失败',
          status: 'error',
        }
      },
      shouldRetry: error => !(error instanceof UserDataSyncError && error.code !== 'remote_failed'),
    })
    scheduler = activeScheduler

    watch(uid, (current) => {
      sessionGeneration += 1
      activeScheduler.stop()
      state.value = { ...state.value, scopeRevision: state.value.scopeRevision + 1 }
      try {
        selectLocalScope(current)
      }
      catch {
        return
      }
      state.value = { ...state.value, errorMessage: '', lastSyncedAt: 0, status: 'idle' }
      if (current) {
        activeScheduler.request()
        return
      }
      activeScheduler.stop()
      state.value = { ...state.value, errorMessage: '', lastSyncedAt: 0, status: 'idle' }
    }, { immediate: true, flush: 'sync' })

    watch(
      () => [
        favoriteEntries.value,
        recipeHistories.value,
        userDataMeta.value.favoriteRemovals,
        userDataMeta.value.historyRemovals,
        userDataMeta.value.historyClearedAt,
      ],
      () => {
        if (!applyingRemote && uid.value && scopeReady)
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
    scopeBlocked: computed(() => state.value.scopeBlocked),
    scopeRevision: computed(() => state.value.scopeRevision),
    syncNow,
  }
}
