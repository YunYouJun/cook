import type { RecipeItem } from '../types/index.js'
import type { RecipeDictionary } from './validate.js'
import { describe, expect, it } from 'vitest'
import { validateRecipes } from './validate.js'

const dict: RecipeDictionary = {
  stuff: new Set(['番茄', '鸡蛋', '虾', '土豆', '牛肉']),
  tools: new Set(['一口大锅', '电饭煲']),
}

function recipe(partial: Partial<RecipeItem>): RecipeItem {
  return { name: '番茄炒蛋', stuff: ['番茄', '鸡蛋'], bv: 'BV1234567890', difficulty: '简单', methods: ['炒'], tools: ['一口大锅'], ...partial }
}

function messages(recipes: RecipeItem[], level: 'error' | 'warning' = 'error') {
  return validateRecipes(recipes, dict).issues.filter(i => i.level === level).map(i => i.message)
}

describe('validateRecipes', () => {
  it('accepts a clean recipe', () => {
    expect(validateRecipes([recipe({})], dict).issues).toEqual([])
  })

  it('flags unknown stuff and suggests the alias target', () => {
    const errors = messages([recipe({ stuff: ['虾仁', '葫芦卜'] })])
    expect(errors[0]).toContain('标准名「虾」')
    expect(errors[1]).toContain('不在食材词典中')
  })

  it('flags wrong separators', () => {
    expect(messages([recipe({ stuff: ['牛肉，土豆'] })])[0]).toContain('顿号')
    expect(messages([recipe({ methods: ['炸/煎'] as RecipeItem['methods'] })])[0]).toContain('顿号')
  })

  it('flags unknown tools, bad difficulty and bad video ids', () => {
    const errors = messages([recipe({ tools: ['高压锅'], difficulty: '复杂' as RecipeItem['difficulty'], bv: 'BV123' })])
    expect(errors).toHaveLength(3)
  })

  it('accepts legacy av ids', () => {
    expect(messages([recipe({ bv: 'av895289430' })])).toEqual([])
  })

  it('warns on duplicates without failing', () => {
    expect(messages([recipe({}), recipe({})])).toEqual([])
    expect(messages([recipe({}), recipe({})], 'warning')[0]).toContain('第 2 行重复')
    expect(messages([recipe({}), recipe({ bv: 'BV0987654321' })], 'warning')[0]).toContain('同名')
  })

  it('counts missing optional fields', () => {
    const { stats } = validateRecipes([recipe({ difficulty: '', methods: [], bv: undefined })], dict)
    expect(stats).toEqual({ total: 1, missingDifficulty: 1, missingMethods: 1, missingVideo: 1 })
  })
})
