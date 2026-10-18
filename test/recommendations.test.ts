import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { describe, expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { recommendRecipes } from '../packages/cook/src/utils/recommend'

// Use tracked CSV so this regression test does not depend on generated JSON files.
const recipes = parseRecipeCsv(readFileSync(resolve(process.cwd(), 'app/data/recipe.csv'), 'utf8'))
const cases = [
  { stuff: ['番茄', '鸡蛋'], counts: [40, 72, 48], first: '微波炉版番茄鸡蛋汤', partial: '番茄鸡蛋', missing: ['米'] },
  { stuff: ['土豆'], counts: [30, 30, 19], first: '烤箱版M记同款薯条', partial: '电饭煲版焖鸡腿土豆', missing: ['鸡肉'] },
  { stuff: ['腊肠', '米'], counts: [6, 30, 26], first: '电饭煲版广式腊肠煲饭', partial: '电饭煲版胡萝卜腊肠饭', missing: ['胡萝卜'] },
  { stuff: ['鸡肉', '洋葱', '菌菇'], counts: [62, 34, 25], first: '电饭煲版烧鸡', partial: '电饭煲版南瓜鸡腿焖饭', missing: ['米'] },
  { stuff: ['牛肉', '番茄', '米'], counts: [13, 55, 50], first: '电饭煲版番茄牛腩焖饭', partial: '电饭煲版番茄炖排骨', missing: ['猪肉'] },
]

describe('local recipe dataset acceptance', () => {
  it.each(cases)('$stuff: verifies every group and representative missing ingredients', ({ stuff, counts, first, partial, missing }) => {
    const matches = recommendRecipes(recipes, { stuff, pantry: ['油', '盐'] })
    expect([0, 1, 2].map(count => matches.filter(m => m.missingStuff.length === count).length)).toEqual(counts)
    expect(matches[0]?.recipe.name).toBe(first)
    expect(matches.find(m => m.recipe.name === partial)?.missingStuff).toEqual(missing)
    for (const match of matches) {
      expect(new Set([...match.matchedStuff, ...match.pantryStuff, ...match.missingStuff])).toEqual(new Set(match.requiredStuff))
      expect(match.matchedStuff.length).toBeGreaterThan(0)
    }
  })

  it('aliases and duplicate selection produce identical results', () => {
    expect(recommendRecipes(recipes, { stuff: ['西红柿', '番茄', '鸡蛋', '鸡蛋'] })).toEqual(recommendRecipes(recipes, { stuff: ['番茄', '鸡蛋'] }))
  })

  it('does not invent condiments absent from this dataset', () => {
    expect(recommendRecipes(recipes, { stuff: ['土豆'], pantry: [] })).toEqual(recommendRecipes(recipes, { stuff: ['土豆'], pantry: ['油', '盐', '醋', '蒜'] }))
  })

  it.each([[], ['火龙果']])('handles empty or unmatched selection %j', (...stuff) => {
    expect(recommendRecipes(recipes, { stuff })).toEqual([])
  })
})
