import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { describe, expect, it } from 'vitest'
import { createEmptyUserData, mergeUserData, normalizeUserData } from '../apps/web/app/utils/userData'
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
