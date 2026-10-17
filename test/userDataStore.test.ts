import type { RecipeItem } from '../app/types'
import type { DbRecipeItem } from '../app/utils/db'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createCloudbaseUserDataRemote, USER_DATA_FUNCTION } from '../app/adapters/userData'
import { favoriteEntries, setFavoriteEntries, toggleFavorite } from '../app/composables/store/favorite'
import { addRecipeHistory, clearRecipeHistories, recipeHistories, removeRecipeHistory } from '../app/composables/store/history'
import { exportLocalUserData, importLocalUserData, localUserData } from '../app/composables/store/userData'
import { userDataMeta } from '../app/composables/store/userDataMeta'

vi.mock('@capacitor/browser', () => ({ Browser: {} }))
vi.mock('@capacitor/preferences', () => ({ Preferences: {} }))

const NOW = 1_800_000_000_000

function recipe(name: string, id?: number): DbRecipeItem {
  return { id, name, stuff: [], tools: [] }
}

beforeEach(() => {
  vi.useFakeTimers({ now: NOW })
  setFavoriteEntries([])
  recipeHistories.value = []
  userDataMeta.value = { favoriteRemovals: [], historyRemovals: [], historyClearedAt: 0, lastUid: '' }
  return () => vi.useRealTimers()
})

describe('favorite tombstones', () => {
  it('records a removal and clears it when favorited again', () => {
    toggleFavorite(recipe('A', 1))
    expect(favoriteEntries.value).toEqual([{ id: 1, time: NOW }])
    expect(userDataMeta.value.favoriteRemovals).toEqual([])

    vi.advanceTimersByTime(10)
    toggleFavorite(recipe('A', 1))
    expect(favoriteEntries.value).toEqual([])
    expect(userDataMeta.value.favoriteRemovals).toEqual([{ id: 1, time: NOW + 10 }])

    vi.advanceTimersByTime(10)
    toggleFavorite(recipe('A', 1))
    expect(userDataMeta.value.favoriteRemovals).toEqual([])
  })
})

describe('history tombstones', () => {
  it('records single removals', () => {
    addRecipeHistory(recipe('A'))
    removeRecipeHistory(recipeHistories.value[0]!)
    expect(recipeHistories.value).toEqual([])
    expect(userDataMeta.value.historyRemovals).toEqual([{ name: 'A', time: NOW }])
  })

  it('records a clear watermark and drops single removals', () => {
    addRecipeHistory(recipe('A'))
    removeRecipeHistory(recipeHistories.value[0]!)
    addRecipeHistory(recipe('B'))
    clearRecipeHistories()
    expect(recipeHistories.value).toEqual([])
    expect(userDataMeta.value.historyClearedAt).toBe(NOW)
    expect(userDataMeta.value.historyRemovals).toEqual([])
  })
})

describe('export / import', () => {
  it('merges an export from another device into local data', () => {
    toggleFavorite(recipe('A', 1))
    addRecipeHistory(recipe('A'))
    const file = exportLocalUserData()

    setFavoriteEntries([{ id: 2, time: NOW - 5 }])
    recipeHistories.value = []
    const result = importLocalUserData(JSON.stringify(file))

    expect(result).toEqual({ favorites: 2, histories: 1 })
    expect(favoriteEntries.value.map(f => f.id)).toEqual([2, 1])
    expect(recipeHistories.value.map(h => h.recipe.name)).toEqual(['A'])
  })

  it('leaves local data alone for an unrecognised file', () => {
    toggleFavorite(recipe('A', 1))
    expect(importLocalUserData('{"favorites":[]}')).toBeNull()
    expect(favoriteEntries.value).toEqual([{ id: 1, time: NOW }])
  })

  it('applies imported deletions', () => {
    toggleFavorite(recipe('A', 1))
    const file = exportLocalUserData()
    file.favorites = []
    file.favoriteRemovals = [{ id: 1, time: NOW + 1 }]

    importLocalUserData(JSON.stringify(file))
    expect(favoriteEntries.value).toEqual([])
  })
})

describe('localUserData', () => {
  it('only rewrites the last uid when it changes', () => {
    const before = userDataMeta.value
    localUserData.writeLastUid('')
    expect(userDataMeta.value).toBe(before)
    localUserData.writeLastUid('u1')
    expect(localUserData.readLastUid()).toBe('u1')
  })
})

describe('createCloudbaseUserDataRemote', () => {
  const document = {
    version: 1 as const,
    favorites: [{ id: 1, time: NOW }],
    favoriteRemovals: [],
    histories: [{ recipe: { name: 'A', stuff: [], tools: [] } as RecipeItem, time: NOW }],
    historyRemovals: [],
    historyClearedAt: 0,
    updatedAt: NOW,
  }

  it('reads through the cook-user-data function without naming a uid', async () => {
    const callFunction = vi.fn(async () => ({ ok: true, document: { favorites: [] } }))
    const remote = createCloudbaseUserDataRemote(callFunction)

    await expect(remote.read('u1')).resolves.toEqual({ favorites: [] })
    expect(callFunction).toHaveBeenCalledWith(USER_DATA_FUNCTION, { action: 'get' })
  })

  it('returns null when the account has no document', async () => {
    const remote = createCloudbaseUserDataRemote(async () => ({ ok: true, document: null }))
    await expect(remote.read('u1')).resolves.toBeNull()
  })

  it('writes the whole document', async () => {
    const callFunction = vi.fn(async () => ({ ok: true, updatedAt: NOW }))
    await createCloudbaseUserDataRemote(callFunction).write('u1', document)
    expect(callFunction).toHaveBeenCalledWith(USER_DATA_FUNCTION, { action: 'set', document })
  })

  it('surfaces function failures', async () => {
    const remote = createCloudbaseUserDataRemote(async () => ({ ok: false, code: 'LOGIN_REQUIRED' }))
    await expect(remote.read('u1')).rejects.toThrow('LOGIN_REQUIRED')
    await expect(remote.write('u1', document)).rejects.toThrow('LOGIN_REQUIRED')
    await expect(createCloudbaseUserDataRemote(async () => null).read('u1')).rejects.toThrow('unknown')
  })
})
