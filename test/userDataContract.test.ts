import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { describe, expect, it } from 'vitest'
import { clearUserDataHistory, createEmptyUserData, mergeUserData, normalizeUserData, parseUserDataExport } from '../apps/web/app/utils/userData'
import importFixture from '../packages/data/fixtures/user-data-file-import.json'
import historyClearFixtures from '../packages/data/fixtures/user-data-history-clear.json'
import boundaries from '../packages/data/fixtures/user-data-sync-boundaries.json'
import fixtures from '../packages/data/fixtures/user-data-sync.json'

const server = createRequire(import.meta.url)('../cloudfunctions/cook-user-data/user-data.cjs') as {
  mergeUserData: typeof mergeUserData
  normalizeUserData: typeof normalizeUserData
}

describe('shared user data contract', () => {
  it('keeps the deployable Node module generated from the Web implementation', () => {
    execFileSync(process.execPath, ['scripts/generate-user-data-contract.mjs', '--check'])
  })

  it('identifies the same active file records as the native clients', () => {
    const file = parseUserDataExport(JSON.stringify(importFixture.file))!
    const result = mergeUserData(file, createEmptyUserData(), importFixture.file.exportedAt)
    expect(result.favorites).toEqual(importFixture.expectedFavorites)
    expect(result.histories.map(({ recipe, time }) => ({ name: recipe.name, time }))).toEqual(importFixture.expectedHistories)
  })

  it.each(fixtures)('$name', ({ a, b, now, expected }) => {
    for (const implementation of [{ mergeUserData, normalizeUserData }, server]) {
      const left = implementation.normalizeUserData(a)!
      const right = implementation.normalizeUserData(b)!
      const result = implementation.normalizeUserData(expected)!
      expect(implementation.mergeUserData(left, right, now)).toEqual(result)
      expect(implementation.mergeUserData(right, left, now)).toEqual(result)
      expect(implementation.mergeUserData(result, left, now)).toEqual(result)
    }
  })

  it.each(historyClearFixtures)('$name', ({ current, peer, now, expectedCutoff, expectedRemovals, expectedVisits }) => {
    const local = normalizeUserData(current)!
    const cleared = { ...local, ...clearUserDataHistory(local, now) }
    expect(cleared.histories).toEqual([])
    expect(cleared.historyClearedAt).toBe(expectedCutoff)
    expect(cleared.historyRemovals).toEqual(expectedRemovals)
    expect(clearUserDataHistory(cleared, now)).toEqual(clearUserDataHistory(local, now))
    for (const implementation of [mergeUserData, server.mergeUserData]) {
      const merged = implementation(cleared, normalizeUserData(peer)!, now)
      expect(merged.histories.map(({ recipe, time }) => ({ name: recipe.name, time }))).toEqual(expectedVisits)
      expect(merged.favorites).toEqual(local.favorites)
    }
  })

  it.each(boundaries)('$name converges in both orders and on repeated merges', (fixture) => {
    function document(start: number, count = fixture.count) {
      const value = createEmptyUserData()
      const ids = Array.from({ length: count }, (_, index) => start + index)
      if (fixture.kind === 'favorites')
        value.favoriteRemovals = ids.map(id => ({ id, time: fixture.now }))
      else
        value.historyRemovals = ids.map(id => ({ name: `recipe-${String(id).padStart(4, '0')}`, time: fixture.now }))
      return value
    }
    const a = document(fixture.leftStart)
    const b = document(fixture.rightStart)
    const expected = document(fixture.expectedStart, fixture.expectedCount)
    for (const implementation of [{ mergeUserData, normalizeUserData }, server]) {
      expect(implementation.mergeUserData(a, b, fixture.now)).toEqual(expected)
      expect(implementation.mergeUserData(b, a, fixture.now)).toEqual(expected)
      expect(implementation.mergeUserData(expected, b, fixture.now)).toEqual(expected)
    }
  })
})
