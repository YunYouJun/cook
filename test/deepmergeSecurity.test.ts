// @vitest-environment node

import { createRequire } from 'node:module'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'

// Exercise the actual patched dependency used by our declared build tool.
const fromTest = createRequire(import.meta.url)
const fromCook = createRequire(fromTest.resolve('../packages/cook/package.json'))
const fromUnbuild = createRequire(fromCook.resolve('unbuild'))
const fromResolver = createRequire(fromUnbuild.resolve('@rollup/plugin-node-resolve'))
const packageRoot = dirname(fromResolver.resolve('deepmerge/package.json'))
type Merge = (target: object, source: object) => Record<PropertyKey, unknown>

describe.each(['dist/cjs.js', 'dist/umd.js'])('deepmerge security: %s', (entry) => {
  const merge = fromResolver(join(packageRoot, entry)) as Merge
  const poisoned = () => JSON.parse('{"__proto__":{"cookPoisoned":true},"safe":1}')

  it('rejects prototype keys from a source when the target has no prototype', () => {
    const result = merge(Object.create(null), poisoned())
    expect(Object.getPrototypeOf(result)).toBe(Object.prototype)
    expect(result.cookPoisoned).toBeUndefined()
    expect(result.safe).toBe(1)
  })

  it('rejects prototype keys copied from the target', () => {
    const result = merge(poisoned(), { added: 2 })
    expect(Object.getPrototypeOf(result)).toBe(Object.prototype)
    expect(result.cookPoisoned).toBeUndefined()
    expect(result).toEqual({ safe: 1, added: 2 })
  })

  it('protects nested objects while preserving normal merges and cloning', () => {
    const source = { nested: poisoned(), list: [{ value: 2 }] }
    const target = { nested: { existing: 3 }, list: [{ value: 1 }] }
    const result = merge(target, source)
    expect(result).toEqual({ nested: { existing: 3, safe: 1 }, list: [{ value: 1 }, { value: 2 }] })
    expect(Object.getPrototypeOf(result.nested)).toBe(Object.prototype)
    expect(result.nested).not.toBe(source.nested)
    expect(result.list).not.toBe(target.list)
    expect(merge({}, {}).cookPoisoned).toBeUndefined()
  })

  it('preserves enumerable symbols and special values', () => {
    const key = Symbol('build-option')
    const date = new Date('2026-11-11T00:00:00Z')
    expect(merge({ [key]: { first: true } }, { [key]: { second: true }, date }))
      .toEqual({ [key]: { first: true, second: true }, date })
  })
})
