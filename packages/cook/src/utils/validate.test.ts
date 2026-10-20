import type { RecipeItem } from '../types/index.js'
import type { RecipeDictionary } from './validate.js'
import { describe, expect, it } from 'vitest'
import { validateRecipes } from './validate.js'

const dict: RecipeDictionary = {
  stuff: new Set(['番茄', '鸡蛋', '虾', '土豆', '牛肉']),
  tools: new Set(['一口大锅', '电饭煲']),
}

function recipe(partial: Partial<RecipeItem>): RecipeItem {
  return { id: 0, name: '番茄炒蛋', stuff: ['番茄', '鸡蛋'], bv: 'BV1234567890', difficulty: '简单', methods: ['炒'], tools: ['一口大锅'], ...partial }
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
    expect(messages([recipe({}), recipe({ id: 1 })])).toEqual([])
    expect(messages([recipe({}), recipe({ id: 1 })], 'warning')[0]).toContain('第 2 行重复')
    expect(messages([recipe({}), recipe({ id: 1, bv: 'BV0987654321' })], 'warning')[0]).toContain('同名')
  })

  it('accepts text recipes and checks their links', () => {
    expect(messages([recipe({ bv: undefined, link: 'https://example.com/a.md' })])).toEqual([])
    expect(messages([recipe({ link: 'example.com/a.md' })])[0]).toContain('https://')
    const { stats } = validateRecipes([recipe({ bv: undefined, link: 'https://example.com/a.md' })], dict)
    expect(stats.missingVideo).toBe(0)
  })

  it('compares text-only recipes by link when looking for duplicates', () => {
    const a = recipe({ bv: undefined, link: 'https://example.com/a.md' })
    expect(messages([a, { ...a, id: 1 }], 'warning')[0]).toContain('重复')
    expect(messages([a, { ...a, id: 1, link: 'https://example.com/b.md' }], 'warning')[0]).toContain('同名')
  })

  it('counts missing optional fields', () => {
    const { stats } = validateRecipes([recipe({ difficulty: '', methods: [], bv: undefined })], dict)
    expect(stats).toEqual({ total: 1, missingDifficulty: 1, missingMethods: 1, missingVideo: 1 })
  })

  it('requires unique ids', () => {
    expect(messages([recipe({ id: undefined })])[0]).toContain('缺少 id')
    expect(messages([recipe({}), recipe({ name: '炒饭', bv: 'BV0987654321' })])[0]).toContain('id 0 与第 2 行重复')
  })
})

describe('validateRecipes with base', () => {
  const base = [recipe({ id: 0 }), recipe({ id: 1, name: '炒饭', bv: 'BV0987654321' })]
  const check = (recipes: RecipeItem[]) => validateRecipes(recipes, dict, { base }).issues

  it('allows renames and new ids', () => {
    expect(check([recipe({ name: '西红柿炒蛋' }), base[1]!, recipe({ id: 2, name: '蛋汤', bv: 'BV1111111111' })])).toEqual([])
  })

  it('rejects giving an id to another recipe or reusing a deleted id', () => {
    const errors = check([recipe({ id: 0, name: '蛋汤', bv: 'BV1111111111' })]).filter(i => i.level === 'error')
    expect(errors[0]?.message).toContain('原来是「番茄炒蛋」')
    expect(check([recipe({}), recipe({ id: 1, name: '蛋汤', bv: 'BV1111111111' })])[0]?.message).toContain('原来是「炒饭」')
  })

  it('rejects reusing the id of a deleted recipe', () => {
    const withGap = [recipe({ id: 0 }), recipe({ id: 2, name: '炒饭', bv: 'BV0987654321' })]
    const issues = validateRecipes([...withGap, recipe({ id: 1, name: '蛋汤', bv: 'BV1111111111' })], dict, { base: withGap }).issues
    expect(issues[0]?.message).toContain('已删除菜谱的 id')
  })

  it('warns when a recipe is deleted', () => {
    expect(check([recipe({})])[0]).toMatchObject({ level: 'warning', message: expect.stringContaining('删除了 id 1') })
  })
})
