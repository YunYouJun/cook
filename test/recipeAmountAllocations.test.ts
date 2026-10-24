import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import type { AmountAnnotations } from '../scripts/lib/recipe-amounts'
import fs from 'node:fs'
import { describe, expect, it } from 'vitest'
import { applyReviewedAmountAllocations } from '../scripts/lib/recipe-amount-followup'
import { matchesAnchor } from '../scripts/lib/recipe-amounts'

const texts: RecipeTextData = JSON.parse(fs.readFileSync('packages/data/recipe-text.json', 'utf8'))
const data: AmountAnnotations = JSON.parse(fs.readFileSync('packages/data/recipe-amounts.json', 'utf8'))

describe('reviewed source allocations', () => {
  it('reproduces stored relations without rewriting source or unrelated recipes', () => {
    const before = JSON.stringify(texts)
    const copy = structuredClone(data)
    const reviews = applyReviewedAmountAllocations(texts, copy)
    expect(copy).toEqual(data)
    expect(JSON.stringify(texts)).toBe(before)
    expect(reviews).toHaveLength(50)
    expect(new Set(reviews.map(r => r.id)).size).toBe(18)
    for (const review of reviews) {
      expect(review.evidence.length).toBeGreaterThan(0)
      for (const evidence of review.evidence)
        expect(matchesAnchor(evidence, texts.recipes[review.id]!)).toBe(true)
      for (const use of review.usages)
        expect(data.recipes[review.id]!.usages).toContainEqual(use)
    }
  })

  it('rejects changes elsewhere in a reviewed source instead of automatically signing again', () => {
    const changed = structuredClone(texts)
    changed.recipes['710']!.blocks.push({ type: 'paragraph', text: '额外加入一份油' })
    expect(() => applyReviewedAmountAllocations(changed, structuredClone(data))).toThrow('710 第二轮分配依据已变化')
  })

  it('retains unknown allocations, incompatible milk units and branch choices', () => {
    const copy = structuredClone(data)
    const reviews = applyReviewedAmountAllocations(texts, copy)
    expect(reviews.find(r => r.id === 799 && r.ingredient === '小葱')?.complete).toBe(false)
    expect(copy.recipes['799']!.usages.filter(u => u.ingredient === '小葱').map(u => u.kind)).toEqual(['unknown', 'unknown'])
    expect(copy.recipes['851']).toEqual(data.recipes['851'])
    expect(copy.recipes['828']).toEqual(data.recipes['828'])
    for (const name of ['味淋', '料酒', '老抽'])
      expect(copy.recipes['817']!.quantities.find(q => q.name === name)?.conditional).toBe(true)
    expect(copy.recipes['817']!.quantities.some(q => q.name === '蚝油')).toBe(false)
    expect(copy.recipes['817']!.quantities.find(q => q.name === '耗油')?.formula).toEqual({ op: 'literal', lower: 20, unit: 'g' })
  })
})
