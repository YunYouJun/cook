// @vitest-environment node
import type { RecommendationOptions } from '../packages/cook/src/utils/recommend'
import fs from 'node:fs'
import { describe, expect, it } from 'vitest'
import { compactRecipe, expandRecipe } from '../docs/.vitepress/theme/landing-data'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { createRecipeRecommender, parseCookQuery } from '../packages/cook/src/utils/recommend'
import { applyRecipeScores } from '../packages/cook/src/utils/score'
import pantry from '../packages/data/pantry.json'

const recipes = applyRecipeScores(
  parseRecipeCsv(fs.readFileSync(new URL('../packages/data/recipe.csv', import.meta.url), 'utf8')),
  JSON.parse(fs.readFileSync(new URL('../packages/data/video-stats.json', import.meta.url), 'utf8')),
)
const compact = JSON.parse(JSON.stringify(recipes.map(compactRecipe))).map(expandRecipe)
const originalRecommend = createRecipeRecommender(recipes)
const demoRecommend = createRecipeRecommender(compact)

describe('landing page recommendation data', () => {
  it('keeps working recipe destinations for the kitchen menu', () => {
    expect(compact.map(recipe => ({ bv: recipe.bv, link: recipe.link })))
      .toEqual(recipes.map(recipe => ({ bv: recipe.bv, link: recipe.link })))
    expect(compact.some(recipe => recipe.bv || recipe.link)).toBe(true)
  })

  it('preserves identifiers for recipes that share the same display name', () => {
    expect(new Set(recipes.map(recipe => recipe.name)).size).toBeLessThan(recipes.length)
    expect(compact.map(recipe => recipe.id)).toEqual(recipes.map(recipe => recipe.id))
    expect(new Set(compact.map(recipe => recipe.id)).size).toBe(recipes.length)
  })

  const scenarios: RecommendationOptions[] = [
    { stuff: ['土豆', '番茄', '鸡蛋'] },
    { stuff: ['豆腐', '菠菜'] },
    { ...parseCookQuery('冰箱里有半颗白菜两个蛋，不要辣') },
    { stuff: ['豆腐', '猪肉'], noSpicy: true },
    { stuff: ['番茄', '鸡蛋'], methodGroups: ['汤羹'] },
  ]

  it.each(scenarios)('matches the full catalog for %j', (options) => {
    const query = { pantry: pantry.defaults, ...options }
    const summary = (recommender: ReturnType<typeof createRecipeRecommender>) => recommender(query)
      .map(match => ({ id: match.recipe.id, missing: match.missingStuff, matched: match.matchedStuff }))
    expect(summary(demoRecommend)).toEqual(summary(originalRecommend))
    expect(summary(demoRecommend).length).toBeGreaterThan(0)
  })
})
