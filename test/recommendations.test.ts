import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import process from 'node:process'
import { describe, expect, it } from 'vitest'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { CATCH_ALL_MIN_STUFF, createRecipeRecommender, hasAvoidedStuff, matchesTaste, recommendRecipes } from '../packages/cook/src/utils/recommend'

// Use tracked CSV so this regression test does not depend on generated JSON files.
const recipes = parseRecipeCsv(readFileSync(resolve(process.cwd(), 'packages/data/recipe.csv'), 'utf8'))
const cases = [
  { stuff: ['番茄', '鸡蛋'], counts: [57, 101, 63], first: '微波炉版番茄鸡蛋汤', partial: '番茄鸡蛋', missing: ['米'] },
  { stuff: ['土豆'], counts: [31, 35, 26], first: '烤箱版M记同款薯条', partial: '电饭煲版焖鸡腿土豆', missing: ['鸡肉'] },
  { stuff: ['腊肠', '米'], counts: [7, 39, 30], first: '电饭煲版广式腊肠煲饭', partial: '电饭煲版胡萝卜腊肠饭', missing: ['胡萝卜'] },
  { stuff: ['鸡肉', '洋葱', '菌菇'], counts: [83, 49, 29], first: '电饭煲版烧鸡', partial: '电饭煲版南瓜鸡腿焖饭', missing: ['米'] },
  { stuff: ['牛肉', '番茄', '米'], counts: [21, 70, 62], first: '电饭煲版番茄牛腩焖饭', partial: '电饭煲版番茄炖排骨', missing: ['猪肉'] },
]

describe('local recipe dataset acceptance', () => {
  it('reuses the prepared catalog across all modes and filters without stale selections', () => {
    const recommend = createRecipeRecommender(recipes)
    for (const { stuff } of cases) {
      for (const mode of ['loose', 'strict', 'survival'] as const) {
        for (const tool of ['', '电饭煲', '一口大锅']) {
          const options = { stuff, mode, tool, pantry: ['油', '盐'] }
          expect(recommend(options)).toEqual(recommendRecipes(recipes, options))
        }
      }
    }
    expect(recommend({ stuff: [] })).toEqual([])
    expect(recommend({ stuff: ['火龙果'] })).toEqual([])
    expect(recommend({ stuff: ['鸡蛋'], keyword: '不存在的菜谱' })).toEqual([])
  })

  it.each(cases)('$stuff: verifies every group and representative missing ingredients', ({ stuff, counts, first, partial, missing }) => {
    const matches = recommendRecipes(recipes, { stuff, pantry: ['油', '盐'] })
    expect([0, 1, 2].map(count => matches.filter(m => m.missingStuff.length === count).length)).toEqual(counts)
    expect(matches[0]?.recipe.name).toBe(first)
    expect(matches.find(m => m.recipe.name === partial)?.missingStuff).toEqual(missing)
    for (const match of matches) {
      expect(new Set([...match.matchedStuff, ...match.pantryStuff, ...match.missingStuff, ...match.optionalStuff])).toEqual(new Set(match.requiredStuff))
      expect(match.matchedStuff.length).toBeGreaterThan(0)
    }
  })

  it('aliases and duplicate selection produce identical results', () => {
    expect(recommendRecipes(recipes, { stuff: ['西红柿', '番茄', '鸡蛋', '鸡蛋'] })).toEqual(recommendRecipes(recipes, { stuff: ['番茄', '鸡蛋'] }))
  })

  it('does not invent condiments absent from this dataset', () => {
    expect(recommendRecipes(recipes, { stuff: ['土豆'], pantry: [] })).toEqual(recommendRecipes(recipes, { stuff: ['土豆'], pantry: ['油', '盐', '醋', '蒜'] }))
  })

  // #72: 兜底大杂烩只列「能放什么」，不该显示成「还缺」，也不该挤到正经菜前面
  it('treats catch-all hotpot recipes as optional add-ins', () => {
    const name = '清汤锅万能高汤做法（鸡蛋+猪肉）'
    expect(recommendRecipes(recipes, { stuff: ['猪肉'] }).some(m => m.recipe.name === name)).toBe(false)

    const matches = recommendRecipes(recipes, { stuff: ['猪肉', '鸡蛋'] })
    const hotpot = matches.find(m => m.recipe.name === name)
    expect(hotpot?.missingStuff).toEqual([])
    expect(hotpot?.optionalStuff).toContain('豆腐')
    const complete = matches.filter(m => !m.missingStuff.length)
    const firstCatchAll = complete.findIndex(m => m.requiredStuff.length >= CATCH_ALL_MIN_STUFF)
    expect(complete.slice(firstCatchAll).every(m => m.requiredStuff.length >= CATCH_ALL_MIN_STUFF)).toBe(true)
  })

  it('keeps regular recipes strict about missing ingredients', () => {
    for (const match of recommendRecipes(recipes, { stuff: ['猪肉', '鸡蛋', '土豆'] })) {
      if (match.requiredStuff.length < CATCH_ALL_MIN_STUFF)
        expect(match.optionalStuff).toEqual([])
    }
  })

  // #85: 忌口
  it('drops regular recipes that use an avoided ingredient, aliases included', () => {
    const matches = recommendRecipes(recipes, { stuff: ['番茄', '鸡蛋'], avoid: ['蘑菇'] })
    expect(matches.length).toBeGreaterThan(0)
    for (const match of matches) {
      if (match.requiredStuff.length < CATCH_ALL_MIN_STUFF)
        expect(match.requiredStuff).not.toContain('菌菇')
    }
    expect(matches.length).toBeLessThan(recommendRecipes(recipes, { stuff: ['番茄', '鸡蛋'] }).length)
  })

  it('keeps catch-all recipes but stops suggesting avoided add-ins', () => {
    const name = '清汤锅万能高汤做法（鸡蛋+猪肉）'
    const hotpot = recommendRecipes(recipes, { stuff: ['猪肉', '鸡蛋'], avoid: ['虾'] }).find(m => m.recipe.name === name)
    expect(hotpot?.optionalStuff).not.toContain('虾')
    expect(hotpot?.optionalStuff).toContain('豆腐')
  })

  it('hasAvoidedStuff matches the recommendation rule', () => {
    const omelette = recipes.find(r => r.stuff.includes('番茄') && r.stuff.includes('鸡蛋') && r.stuff.length === 2)!
    expect(hasAvoidedStuff(omelette, ['西红柿'])).toBe(true)
    expect(hasAvoidedStuff(omelette, [])).toBe(false)
    expect(hasAvoidedStuff(omelette, ['牛肉'])).toBe(false)
  })

  it('tags spicy and soup recipes and records a method for almost every recipe', () => {
    const byName = (name: string) => recipes.find(r => r.name === name)!
    expect(matchesTaste(byName('麻婆豆腐'), { noSpicy: true })).toBe(false)
    expect(matchesTaste(byName('白萝卜汤'), { methodGroups: ['汤羹'] })).toBe(true)
    expect(matchesTaste(byName('宫保鸡丁'), { methodGroups: ['炒菜'] })).toBe(true)
    expect(recipes.filter(r => !r.methods?.length).length).toBeLessThan(recipes.length * 0.05)
  })

  it.each([[], ['火龙果']])('handles empty or unmatched selection %j', (...stuff) => {
    expect(recommendRecipes(recipes, { stuff })).toEqual([])
  })
})
