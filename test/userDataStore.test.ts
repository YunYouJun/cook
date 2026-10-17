import type { CloudbaseDatabase, CloudbaseDocumentRef } from '../app/adapters/yunlefunAuth'
import type { RecipeItem } from '../app/types'
import type { DbRecipeItem } from '../app/utils/db'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createCloudbaseUserDataRemote } from '../app/adapters/userData'
import { favoriteEntries, setFavoriteEntries, toggleFavorite } from '../app/composables/store/favorite'
import { addRecipeHistory, clearRecipeHistories, recipeHistories, removeRecipeHistory } from '../app/composables/store/history'
import { exportLocalUserData, importLocalUserData, localUserData } from '../app/composables/store/userData'
import { userDataMeta } from '../app/composables/store/userDataMeta'
import { USER_DATA_COLLECTION } from '../app/utils/userDataSync'

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
  function createDatabase(ref: Partial<CloudbaseDocumentRef>) {
    const collection = vi.fn(() => ({ doc: vi.fn(() => ref as CloudbaseDocumentRef) }))
    const database = { collection } as unknown as CloudbaseDatabase
    return { collection, database }
  }

  it('reads the account document from the Cook collection', async () => {
    const doc = { favorites: [] }
    const { collection, database } = createDatabase({ get: async () => ({ data: [doc] }) })
    const remote = createCloudbaseUserDataRemote(async () => database)

    await expect(remote.read('u1')).resolves.toBe(doc)
    expect(collection).toHaveBeenCalledWith(USER_DATA_COLLECTION)
  })

  it('returns null when the document does not exist', async () => {
    const { database } = createDatabase({ get: async () => ({ data: [] }) })
    await expect(createCloudbaseUserDataRemote(async () => database).read('u1')).resolves.toBeNull()
  })

  it('surfaces error codes from reads and writes', async () => {
    const { database } = createDatabase({
      get: async () => ({ code: 'DATABASE_PERMISSION_DENIED' }),
      set: async () => ({ code: 'DATABASE_PERMISSION_DENIED' }),
    })
    const remote = createCloudbaseUserDataRemote(async () => database)
    await expect(remote.read('u1')).rejects.toThrow('DATABASE_PERMISSION_DENIED')
    await expect(remote.write('u1', {
      version: 1,
      favorites: [],
      favoriteRemovals: [],
      histories: [{ recipe: { name: 'A', stuff: [], tools: [] } as RecipeItem, time: NOW }],
      historyRemovals: [],
      historyClearedAt: 0,
      updatedAt: NOW,
    })).rejects.toThrow('DATABASE_PERMISSION_DENIED')
  })

  it('treats a write that touched nothing as a failure', async () => {
    const { database } = createDatabase({ set: async () => ({ updated: 0, upsertedId: null }) })
    const remote = createCloudbaseUserDataRemote(async () => database)
    await expect(remote.write('u1', {
      version: 1,
      favorites: [],
      favoriteRemovals: [],
      histories: [],
      historyRemovals: [],
      historyClearedAt: 0,
      updatedAt: NOW,
    })).rejects.toThrow('not applied')
  })

  it('writes the whole document with set()', async () => {
    const set = vi.fn(async () => ({}))
    const { database } = createDatabase({ set })
    const document = {
      version: 1 as const,
      favorites: [{ id: 1, time: NOW }],
      favoriteRemovals: [],
      histories: [],
      historyRemovals: [],
      historyClearedAt: 0,
      updatedAt: NOW,
    }
    await createCloudbaseUserDataRemote(async () => database).write('u1', document)
    expect(set).toHaveBeenCalledWith(document)
  })
})
