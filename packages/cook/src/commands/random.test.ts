import type { RecipeItem } from '../../types.js'
import { describe, expect, it } from 'vitest'
import { sampleRecipes } from './random.js'

const recipes: RecipeItem[] = Array.from({ length: 10 }, (_, i) => ({
  name: `菜谱${i + 1}`,
  stuff: ['鸡蛋'],
  tools: [],
}))

describe('sampleRecipes', () => {
  it('返回指定数量', () => {
    const result = sampleRecipes(recipes, 3)
    expect(result).toHaveLength(3)
  })

  it('不重复', () => {
    const result = sampleRecipes(recipes, 5)
    const names = result.map(r => r.name)
    expect(new Set(names).size).toBe(5)
  })

  it('count 超出总数时截断为全部', () => {
    const result = sampleRecipes(recipes, 999)
    expect(result).toHaveLength(10)
  })

  it('count=0 返回空数组', () => {
    const result = sampleRecipes(recipes, 0)
    expect(result).toHaveLength(0)
  })

  it('空数组返回空', () => {
    const result = sampleRecipes([], 5)
    expect(result).toHaveLength(0)
  })
})
