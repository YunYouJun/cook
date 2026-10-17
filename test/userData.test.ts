import type { RecipeItem } from '../app/types'
import type { UserData } from '../app/utils/userData'
import { describe, expect, it } from 'vitest'
import {
  createEmptyUserData,
  createUserDataExport,
  HISTORY_LIMIT,
  isSameUserData,
  mergeUserData,
  normalizeUserData,
  parseUserDataExport,
  TOMBSTONE_TTL,
} from '../app/utils/userData'

const NOW = 1_800_000_000_000

function recipe(name: string): RecipeItem {
  return { name, stuff: ['土豆'], tools: ['锅'], bv: `BV${name}` }
}

function data(patch: Partial<UserData>): UserData {
  return { ...createEmptyUserData(), ...patch }
}

describe('mergeUserData favorites', () => {
  it('unions favorites from both sides', () => {
    const merged = mergeUserData(
      data({ favorites: [{ id: 1, time: NOW - 30 }] }),
      data({ favorites: [{ id: 2, time: NOW - 20 }] }),
      NOW,
    )
    expect(merged.favorites).toEqual([{ id: 1, time: NOW - 30 }, { id: 2, time: NOW - 20 }])
  })

  it('lets a later removal on another device win', () => {
    const merged = mergeUserData(
      data({ favorites: [{ id: 1, time: NOW - 30 }] }),
      data({ favoriteRemovals: [{ id: 1, time: NOW - 10 }] }),
      NOW,
    )
    expect(merged.favorites).toEqual([])
    expect(merged.favoriteRemovals).toEqual([{ id: 1, time: NOW - 10 }])
  })

  it('lets a later re-favorite win and drops the stale tombstone', () => {
    const merged = mergeUserData(
      data({ favoriteRemovals: [{ id: 1, time: NOW - 30 }] }),
      data({ favorites: [{ id: 1, time: NOW - 10 }] }),
      NOW,
    )
    expect(merged.favorites).toEqual([{ id: 1, time: NOW - 10 }])
    expect(merged.favoriteRemovals).toEqual([])
  })

  it('keeps the newest favorite time when both sides favorited', () => {
    const merged = mergeUserData(
      data({ favorites: [{ id: 1, time: NOW - 30 }] }),
      data({ favorites: [{ id: 1, time: NOW - 10 }] }),
      NOW,
    )
    expect(merged.favorites).toEqual([{ id: 1, time: NOW - 10 }])
  })

  it('prunes tombstones older than the TTL', () => {
    const merged = mergeUserData(
      data({ favoriteRemovals: [{ id: 1, time: NOW - TOMBSTONE_TTL - 1 }, { id: 2, time: NOW - 1 }] }),
      createEmptyUserData(),
      NOW,
    )
    expect(merged.favoriteRemovals).toEqual([{ id: 2, time: NOW - 1 }])
  })
})

describe('mergeUserData histories', () => {
  it('unions visits, dedupes identical ones and sorts oldest first', () => {
    const merged = mergeUserData(
      data({ histories: [{ recipe: recipe('B'), time: NOW - 10 }, { recipe: recipe('A'), time: NOW - 30 }] }),
      data({ histories: [{ recipe: recipe('A'), time: NOW - 30 }, { recipe: recipe('A'), time: NOW - 5 }] }),
      NOW,
    )
    expect(merged.histories.map(h => [h.recipe.name, h.time])).toEqual([
      ['A', NOW - 30],
      ['B', NOW - 10],
      ['A', NOW - 5],
    ])
  })

  it('drops removed visits and everything before a clear', () => {
    const merged = mergeUserData(
      data({
        histories: [
          { recipe: recipe('A'), time: NOW - 50 },
          { recipe: recipe('B'), time: NOW - 20 },
          { recipe: recipe('C'), time: NOW - 10 },
        ],
      }),
      data({ historyClearedAt: NOW - 40, historyRemovals: [{ name: 'B', time: NOW - 20 }] }),
      NOW,
    )
    expect(merged.histories.map(h => h.recipe.name)).toEqual(['C'])
    expect(merged.historyClearedAt).toBe(NOW - 40)
    expect(merged.historyRemovals).toEqual([{ name: 'B', time: NOW - 20 }])
  })

  it('keeps only the newest visits', () => {
    const histories = Array.from({ length: HISTORY_LIMIT + 5 }, (_, i) => ({ recipe: recipe(`R${i}`), time: NOW - 1000 + i }))
    const merged = mergeUserData(data({ histories }), createEmptyUserData(), NOW)
    expect(merged.histories).toHaveLength(HISTORY_LIMIT)
    expect(merged.histories[0]!.recipe.name).toBe('R5')
  })

  it('rebuilds recipes canonically so local and cloud copies compare equal', () => {
    const local = data({ histories: [{ recipe: { id: 3, ...recipe('A') } as RecipeItem, time: NOW - 1 }] })
    const cloud = normalizeUserData(JSON.parse(JSON.stringify(mergeUserData(local, createEmptyUserData(), NOW))))!
    expect(isSameUserData(mergeUserData(local, cloud, NOW), cloud)).toBe(true)
  })
})

describe('mergeUserData properties', () => {
  const a = data({
    favorites: [{ id: 1, time: NOW - 30 }, { id: 2, time: NOW - 25 }],
    favoriteRemovals: [{ id: 3, time: NOW - 20 }],
    histories: [{ recipe: recipe('A'), time: NOW - 30 }],
    historyRemovals: [{ name: 'B', time: NOW - 15 }],
  })
  const b = data({
    favorites: [{ id: 3, time: NOW - 40 }],
    favoriteRemovals: [{ id: 1, time: NOW - 10 }],
    histories: [{ recipe: recipe('B'), time: NOW - 15 }, { recipe: recipe('C'), time: NOW - 5 }],
    historyClearedAt: NOW - 35,
  })

  it('is commutative', () => {
    expect(isSameUserData(mergeUserData(a, b, NOW), mergeUserData(b, a, NOW))).toBe(true)
  })

  it('is idempotent', () => {
    const once = mergeUserData(a, b, NOW)
    expect(isSameUserData(mergeUserData(once, once, NOW), once)).toBe(true)
    expect(isSameUserData(mergeUserData(once, b, NOW), once)).toBe(true)
  })
})

describe('normalizeUserData', () => {
  it('rejects non-objects and unknown versions', () => {
    expect(normalizeUserData(null)).toBeNull()
    expect(normalizeUserData([])).toBeNull()
    expect(normalizeUserData({ version: 2 })).toBeNull()
  })

  it('drops malformed entries and unknown recipe fields', () => {
    const result = normalizeUserData({
      version: 1,
      favorites: [{ id: 1, time: NOW }, { id: -1, time: NOW }, { id: '2', time: NOW }, 'x'],
      histories: [
        { recipe: { name: 'A', stuff: ['土豆', 3], tools: [], script: '<x>', tags: ['快手'] }, time: NOW },
        { recipe: { stuff: [] }, time: NOW },
      ],
      historyClearedAt: 'soon',
    })
    expect(result).toEqual({
      favorites: [{ id: 1, time: NOW }],
      favoriteRemovals: [],
      histories: [{ recipe: { name: 'A', stuff: ['土豆'], tools: [], tags: ['快手'] }, time: NOW }],
      historyRemovals: [],
      historyClearedAt: 0,
    })
  })
})

describe('export / import', () => {
  it('round-trips through JSON', () => {
    const value = mergeUserData(data({
      favorites: [{ id: 1, time: NOW - 1 }],
      histories: [{ recipe: recipe('A'), time: NOW - 2 }],
    }), createEmptyUserData(), NOW)
    const file = createUserDataExport(value, NOW)
    expect(file).toMatchObject({ app: 'cook', version: 1, exportedAt: NOW })
    expect(parseUserDataExport(JSON.stringify(file))).toEqual(value)
  })

  it('rejects files that are not Cook exports', () => {
    expect(parseUserDataExport('not json')).toBeNull()
    expect(parseUserDataExport(JSON.stringify({ version: 1, favorites: [] }))).toBeNull()
    expect(parseUserDataExport(JSON.stringify({ app: 'cook', version: 9 }))).toBeNull()
  })
})
