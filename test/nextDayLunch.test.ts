import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import type { AmountAnchor } from '../scripts/lib/recipe-amounts'
import { readFileSync } from 'node:fs'
import { expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { searchRecipes } from '../packages/cook/src/utils/search'
import { sourceFingerprint, sourceLines } from '../scripts/lib/recipe-amounts'

interface LunchReview {
  name: string
  sourceLink: string
  sourceFingerprint: string
  referenceServings: number
  servingEvidence: AmountAnchor[]
  mainIngredientEvidence: AmountAnchor[]
  addedOil: { lower: number, upper: number, unit: string, evidence: AmountAnchor[] }
}

const read = (file: string) => JSON.parse(readFileSync(file, 'utf8'))
const review = read('specs/dietary-goals/next-day-lunch.json') as {
  schemaVersion: number
  source: RecipeTextData['source']
  tag: string
  recipes: Record<string, LunchReview>
}
const source = read('packages/data/recipe-text.json') as RecipeTextData
const recipes = parseRecipeCsv(readFileSync('packages/data/recipe.csv', 'utf8'))

// 这份人工核对记录支撑公开标签；正文漂移或未经审核的新增标签须明确报错。
it('keeps next-day lunch tags bound to reviewed portions, oil evidence and complete source text', () => {
  expect(review.schemaVersion).toBe(1)
  expect(review.source).toMatchObject({ name: source.source.name, url: source.source.url, license: source.source.license })
  expect(review.source.commit).toMatch(/^[a-f0-9]{40}$/)
  expect(Object.keys(review.recipes).length).toBeGreaterThanOrEqual(10)
  expect(recipes.filter(recipe => recipe.tags?.includes(review.tag)).map(recipe => String(recipe.id)).sort())
    .toEqual(Object.keys(review.recipes).sort())
  expect(searchRecipes(recipes, [], { tag: review.tag, limit: 1000 }).recipes.map(recipe => String(recipe.id)).sort())
    .toEqual(Object.keys(review.recipes).sort())

  for (const [id, entry] of Object.entries(review.recipes)) {
    const recipe = recipes.find(recipe => String(recipe.id) === id)!
    const text = source.recipes[id]!
    expect(recipe.name, id).toBe(entry.name)
    expect(recipe.link, id).toBe(entry.sourceLink)
    expect(sourceFingerprint(text), id).toBe(entry.sourceFingerprint)
    expect(Number.isInteger(entry.referenceServings) && entry.referenceServings > 0, id).toBe(true)
    expect(entry.servingEvidence.length, id).toBeGreaterThan(0)
    expect(entry.mainIngredientEvidence.length, id).toBeGreaterThan(0)
    expect(entry.addedOil.evidence.length, id).toBeGreaterThan(0)
    expect(['g', 'ml'], id).toContain(entry.addedOil.unit)
    expect(Number.isFinite(entry.addedOil.lower) && entry.addedOil.lower >= 0, id).toBe(true)
    expect(Number.isFinite(entry.addedOil.upper) && entry.addedOil.upper >= entry.addedOil.lower, id).toBe(true)
    const lines = sourceLines(text)
    for (const anchor of [...entry.servingEvidence, ...entry.mainIngredientEvidence, ...entry.addedOil.evidence])
      expect(lines.some(line => line.block === anchor.block && line.item === anchor.item && line.text === anchor.text), `${id}: ${anchor.text}`).toBe(true)
  }
})
