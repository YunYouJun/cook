import type { RecipeItem } from '../app/types'
import { describe, expect, it } from 'vitest'
import { filterClassicRecipes } from '../app/utils/classicRecipes'

const recipes: RecipeItem[] = [
  { name: '土豆杂烩', stuff: ['土豆', '牛肉', '洋葱', '米'], tools: ['电饭煲'] },
  { name: '番茄炒蛋', stuff: ['番茄', '鸡蛋'], tools: ['一口大锅'] },
  { name: '土豆炒蛋', stuff: ['土豆', '鸡蛋'], tools: ['一口大锅'] },
  { name: '煎蛋', stuff: ['鸡蛋'], tools: ['一口大锅'] },
]
const options = { stuff: ['土豆', '鸡蛋'], tool: '', keyword: '', mode: 'loose' as const }

describe('classic view compatibility', () => {
  it('retains any-match semantics, source order and recipes missing more than two ingredients', () => {
    expect(filterClassicRecipes(recipes, options)).toEqual(recipes)
  })

  it('strict mode requires all selected ingredients and survival requires all recorded ingredients', () => {
    expect(filterClassicRecipes(recipes, { ...options, mode: 'strict' }).map(r => r.name)).toEqual(['土豆炒蛋'])
    expect(filterClassicRecipes(recipes, { ...options, mode: 'survival' }).map(r => r.name)).toEqual(['土豆炒蛋', '煎蛋'])
  })

  it('keeps tool-only search, combines tool/keyword, and shows no results for empty selection', () => {
    expect(filterClassicRecipes(recipes, { ...options, stuff: [], tool: '一口大锅' })).toEqual(recipes.slice(1))
    expect(filterClassicRecipes(recipes, { ...options, tool: '一口大锅', keyword: '炒蛋' })).toEqual(recipes.slice(1, 3))
    expect(filterClassicRecipes(recipes, { ...options, stuff: [] })).toEqual([])
  })
})
