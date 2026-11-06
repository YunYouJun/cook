import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { describe, expect, it } from 'vitest'
import { mergeUserData, normalizeUserData } from '../apps/web/app/utils/userData'
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
})
