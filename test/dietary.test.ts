import type { DietaryData } from '../scripts/lib/dietary'
import { createHash } from 'node:crypto'
import fs from 'node:fs'
import { describe, expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { dietaryDataVersion, dietaryRecipeFingerprint, validateDietaryData } from '../scripts/lib/dietary'

const read = (file: string) => JSON.parse(fs.readFileSync(`packages/data/${file}`, 'utf8'))
const data: DietaryData = read('recipe-dietary.json')
const texts = read('recipe-text.json').recipes
const recipes = parseRecipeCsv(fs.readFileSync('packages/data/recipe.csv', 'utf8'))
const amountsFingerprint = createHash('sha256').update(fs.readFileSync('packages/data/recipe-amounts.json')).digest('hex')
const validate = (input: DietaryData, sources = texts) => validateDietaryData(input, recipes, sources, amountsFingerprint)

describe('reviewed dietary source data', () => {
  it('validates every annotation against the current source and opens only supported goals', () => {
    expect(validate(data)).toEqual([])
    expect(data.enabledGoals).toEqual(['less-added-sugar', 'balanced-pairing'])
    expect(Object.keys(data.recipes)).toHaveLength(32)
    expect(Object.values(data.recipes).filter(entry => entry.assessments['less-added-sugar'].status === 'pass')).toHaveLength(12)
    expect(data.recipes['849'].assessments['less-added-sugar'].status).toBe('fail')
    expect(data.recipes['789'].assessments['less-added-sugar'].status).toBe('unknown')
  })

  it('rejects source drift without refreshing the review', () => {
    const sources = structuredClone(texts)
    sources['333'].blocks.push({ type: 'paragraph', text: '来源改动' })
    expect(validate(data, sources)).toContain('333: 图文已变化，需要重新审核')
    const changedRecipes = structuredClone(recipes)
    changedRecipes.find(recipe => recipe.id === 333)!.bv = 'BV1111111111'
    expect(validateDietaryData(data, changedRecipes, texts, amountsFingerprint)).toContain('333: 菜谱字段已变化，需要重新审核')
  })

  it('rejects changed amount annotations even when the text is unchanged', () => {
    expect(validateDietaryData(data, recipes, texts, '0'.repeat(64))).toContain('用量标注已变化，需要核对后更新饮食数据')
  })

  it('rejects forged passing assessments, missing evidence and invalid oil ranges', () => {
    const changed = structuredClone(data)
    changed.recipes['693'].assessments['less-added-sugar'] = { ...changed.recipes['693'].assessments['less-added-sugar'], status: 'pass', reason: 'no-added-sugar' }
    changed.recipes['333'].ingredientEvidence[0].text = '不是原文'
    changed.recipes['800'].portionOil!.batchOilGrams = { lower: 5, upper: 1 }
    const errors = validate(changed)
    expect(errors).toContain('693: 添加糖或未核对复合调料不能标为未添加糖')
    expect(errors).toContain('333: 原料证据与原文不符')
    expect(errors).toContain('800: 份量、添加油数值或证据无效')
  })

  it('does not open a goal or a catalog below the release threshold', () => {
    const changed = structuredClone(data)
    changed.enabledGoals.push('weight-aware')
    expect(validate(changed)).toContain('weight-aware: 开放目标至少需要 10 道通过记录，当前 2')
    for (const id of Object.keys(changed.recipes).slice(0, 3))
      delete changed.recipes[id]
    expect(validate(changed)).toContain('开放目标合计至少需要 30 道独立菜谱，当前 29')
  })

  it('versions annotation-only changes and ignores display scores in source binding', () => {
    const changed = structuredClone(data)
    changed.recipes['333'].reviewedAt = '2026-10-27'
    expect(dietaryDataVersion(changed)).not.toBe(data.dataVersion)
    const recipe = recipes.find(recipe => recipe.id === 333)!
    expect(dietaryRecipeFingerprint({ ...recipe, score: 0 })).toBe(dietaryRecipeFingerprint({ ...recipe, score: 100 }))
    expect(dietaryRecipeFingerprint({ ...recipe, stuff: ['其他原料'] })).not.toBe(dietaryRecipeFingerprint(recipe))
  })
})
