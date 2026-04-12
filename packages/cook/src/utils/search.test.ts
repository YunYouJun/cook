import type { IncompatibleRule, RecipeItem } from '../types/index.js'
import { describe, expect, it } from 'vitest'
import { normalizeStuff } from './alias.js'
import { searchRecipes } from './search.js'

// ─── Test Fixtures ───────────────────────────────────

const recipes: RecipeItem[] = [
  {
    name: '番茄炒蛋',
    stuff: ['番茄', '鸡蛋'],
    bv: 'BV1rf4y1872R',
    difficulty: '普通',
    tags: ['家常菜'],
    methods: ['炒'],
    tools: ['一口大锅'],
  },
  {
    name: '番茄鸡蛋面',
    stuff: ['方便面', '番茄', '鸡蛋'],
    bv: 'BV1tL4y1b7SM',
    difficulty: '简单',
    tags: [],
    methods: ['煮'],
    tools: ['一口大锅'],
  },
  {
    name: '电饭煲版番茄牛腩焖饭',
    stuff: ['牛肉', '番茄', '米'],
    bv: 'BV1Bv411C7X3',
    difficulty: '简单',
    tags: ['懒人'],
    methods: [],
    tools: ['电饭煲'],
  },
  {
    name: '酸辣土豆丝',
    stuff: ['土豆'],
    difficulty: '普通',
    tags: ['家常菜'],
    methods: ['炒'],
    tools: ['一口大锅'],
  },
  {
    name: '空气炸锅烤鸡腿',
    stuff: ['鸡肉'],
    bv: 'BV1Zr4y1B7UQ',
    difficulty: '普通',
    tags: [],
    methods: ['炸'],
    tools: ['空气炸锅'],
  },
  {
    name: '微波炉版番茄鸡蛋汤',
    stuff: ['番茄', '鸡蛋'],
    bv: 'BV1qx411n7QF',
    tags: [],
    methods: [],
    tools: ['微波炉'],
  },
]

const incompatible: IncompatibleRule[] = [
  { foodA: '牛奶', foodB: '韭菜', reason: '牛奶与韭菜同食会影响钙的吸收' },
]

// ─── normalizeStuff ──────────────────────────────────

describe('normalizeStuff', () => {
  it('should normalize known aliases', () => {
    expect(normalizeStuff(['西红柿'])).toEqual(['番茄'])
    expect(normalizeStuff(['泡面'])).toEqual(['方便面'])
    expect(normalizeStuff(['鸡翅'])).toEqual(['鸡肉'])
    expect(normalizeStuff(['排骨'])).toEqual(['猪肉'])
    expect(normalizeStuff(['肥牛'])).toEqual(['牛肉'])
    expect(normalizeStuff(['虾仁'])).toEqual(['虾'])
  })

  it('should keep unknown names unchanged', () => {
    expect(normalizeStuff(['番茄'])).toEqual(['番茄'])
    expect(normalizeStuff(['鸡蛋'])).toEqual(['鸡蛋'])
    expect(normalizeStuff(['鹅肝'])).toEqual(['鹅肝'])
  })

  it('should trim whitespace', () => {
    expect(normalizeStuff([' 西红柿 '])).toEqual(['番茄'])
    expect(normalizeStuff([' 鸡蛋 '])).toEqual(['鸡蛋'])
  })

  it('should handle mixed aliases and regular names', () => {
    expect(normalizeStuff(['西红柿', '鸡蛋', '泡面'])).toEqual(['番茄', '鸡蛋', '方便面'])
  })

  it('should handle empty input', () => {
    expect(normalizeStuff([])).toEqual([])
  })
})

// ─── searchRecipes ───────────────────────────────────

describe('searchRecipes', () => {
  describe('stuff filtering', () => {
    it('should match by single stuff', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄'] })
      expect(result.recipes.length).toBeGreaterThan(0)
      expect(result.recipes.every(r => r.stuff.includes('番茄'))).toBe(true)
    })

    it('should match by multiple stuff', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄', '鸡蛋'] })
      expect(result.recipes.length).toBeGreaterThan(0)
      expect(result.recipes.every(r =>
        r.stuff.includes('番茄') || r.stuff.includes('鸡蛋'),
      )).toBe(true)
    })

    it('should normalize aliases before matching', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['西红柿'] })
      expect(result.recipes.length).toBeGreaterThan(0)
      expect(result.recipes.every(r => r.stuff.includes('番茄'))).toBe(true)
    })

    it('should return empty for unmatched stuff', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['鱼'] })
      expect(result.recipes).toHaveLength(0)
    })

    it('should return all recipes when no stuff specified', () => {
      const result = searchRecipes(recipes, incompatible, {})
      expect(result.recipes).toHaveLength(recipes.length)
    })
  })

  describe('tool filtering', () => {
    it('should filter by tool', () => {
      const result = searchRecipes(recipes, incompatible, { tool: '电饭煲' })
      expect(result.recipes.length).toBeGreaterThan(0)
      expect(result.recipes.every(r => r.tools.some(t => t.includes('电饭煲')))).toBe(true)
    })

    it('should filter by tool with stuff', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄'], tool: '微波炉' })
      expect(result.recipes).toHaveLength(1)
      expect(result.recipes[0].name).toBe('微波炉版番茄鸡蛋汤')
    })
  })

  describe('difficulty filtering', () => {
    it('should filter by difficulty', () => {
      const result = searchRecipes(recipes, incompatible, { difficulty: '简单' })
      expect(result.recipes.every(r => r.difficulty === '简单')).toBe(true)
    })
  })

  describe('tag filtering', () => {
    it('should filter by tag', () => {
      const result = searchRecipes(recipes, incompatible, { tag: '懒人' })
      expect(result.recipes.length).toBeGreaterThan(0)
      expect(result.recipes.every(r => r.tags?.some(t => t.includes('懒人')))).toBe(true)
    })
  })

  describe('method filtering', () => {
    it('should filter by method', () => {
      const result = searchRecipes(recipes, incompatible, { method: '炒' })
      expect(result.recipes.length).toBeGreaterThan(0)
      expect(result.recipes.every(r => r.methods?.some(m => m.includes('炒')))).toBe(true)
    })
  })

  describe('sorting', () => {
    it('should sort by matched stuff count descending', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄', '鸡蛋'] })
      // 完全匹配（2/2 食材）的应排在前面
      const fullMatch = result.recipes.filter(r => r.matchedStuff.length === 2)
      const partialMatch = result.recipes.filter(r => r.matchedStuff.length === 1)
      if (fullMatch.length > 0 && partialMatch.length > 0) {
        const firstPartialIdx = result.recipes.indexOf(partialMatch[0])
        const lastFullIdx = result.recipes.indexOf(fullMatch.at(-1))
        expect(lastFullIdx).toBeLessThan(firstPartialIdx)
      }
    })

    it('should prefer fewer total stuff when matched count is same', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄', '鸡蛋'] })
      // 番茄炒蛋(2食材) 和 微波炉版番茄鸡蛋汤(2食材) 应排在 番茄鸡蛋面(3食材) 前面
      const twoStuff = result.recipes.filter(r => r.stuff.length === 2 && r.matchedStuff.length === 2)
      const threeStuff = result.recipes.filter(r => r.stuff.length === 3 && r.matchedStuff.length === 2)
      if (twoStuff.length > 0 && threeStuff.length > 0) {
        const firstThreeIdx = result.recipes.indexOf(threeStuff[0])
        const lastTwoIdx = result.recipes.indexOf(twoStuff.at(-1))
        expect(lastTwoIdx).toBeLessThan(firstThreeIdx)
      }
    })
  })

  describe('limit', () => {
    it('should respect limit', () => {
      const result = searchRecipes(recipes, incompatible, { limit: 2 })
      expect(result.recipes.length).toBeLessThanOrEqual(2)
    })

    it('should default to 10', () => {
      const result = searchRecipes(recipes, incompatible, {})
      expect(result.recipes.length).toBeLessThanOrEqual(10)
    })
  })

  describe('result fields', () => {
    it('should include matchedStuff and missingStuff', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄'] })
      const r = result.recipes.find(r => r.name === '番茄炒蛋')!
      expect(r.matchedStuff).toEqual(['番茄'])
      expect(r.missingStuff).toEqual(['鸡蛋'])
    })

    it('should generate videoUrl from bv', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄', '鸡蛋'] })
      const r = result.recipes.find(r => r.name === '番茄炒蛋')!
      expect(r.videoUrl).toBe('https://www.bilibili.com/video/BV1rf4y1872R')
    })

    it('should have no videoUrl when bv is missing', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['土豆'] })
      const r = result.recipes.find(r => r.name === '酸辣土豆丝')!
      expect(r.videoUrl).toBeUndefined()
    })
  })

  describe('incompatible warnings', () => {
    it('should warn about incompatible foods', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['牛奶', '韭菜'] })
      expect(result.warnings).toHaveLength(1)
      expect(result.warnings[0].foodA).toBe('牛奶')
      expect(result.warnings[0].foodB).toBe('韭菜')
    })

    it('should not warn for compatible foods', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['番茄', '鸡蛋'] })
      expect(result.warnings).toHaveLength(0)
    })

    it('should not warn with single food', () => {
      const result = searchRecipes(recipes, incompatible, { stuff: ['牛奶'] })
      expect(result.warnings).toHaveLength(0)
    })
  })

  describe('combined filters', () => {
    it('should combine stuff + tool + difficulty', () => {
      const result = searchRecipes(recipes, incompatible, {
        stuff: ['番茄'],
        tool: '一口大锅',
        difficulty: '普通',
      })
      expect(result.recipes.length).toBeGreaterThan(0)
      expect(result.recipes.every(r =>
        r.stuff.includes('番茄')
        && r.tools.some(t => t.includes('一口大锅'))
        && r.difficulty === '普通',
      )).toBe(true)
    })
  })
})
