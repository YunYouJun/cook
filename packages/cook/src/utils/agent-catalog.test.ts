import type { CookCatalogData } from './agent-catalog.js'
import { describe, expect, it } from 'vitest'
import { queryCookCatalog } from './agent-catalog.js'
import { lookupShelfLife } from './shelf-life.js'

const catalog: CookCatalogData = {
  defaultPantry: ['油', '盐'],
  incompatible: [
    { foodA: '牛奶', foodB: '韭菜', reason: '牛奶与韭菜同食会影响钙的吸收' },
  ],
  recipes: [
    {
      id: 1,
      name: '番茄炒蛋',
      stuff: ['番茄', '鸡蛋'],
      bv: 'BV1rf4y1872R',
      difficulty: '简单',
      tags: ['家常菜'],
      methods: ['炒'],
      tools: ['一口大锅'],
    },
    {
      id: 2,
      name: '辣炒土豆丝',
      stuff: ['土豆', '辣椒'],
      bv: 'BV1xx411c7mD',
      difficulty: '普通',
      tags: ['辣', '下饭'],
      methods: ['炒'],
      tools: ['一口大锅'],
    },
    {
      id: 3,
      name: '空气炸锅烤土豆',
      stuff: ['土豆'],
      bv: 'BV1Zr4y1B7UQ',
      difficulty: '简单',
      tags: ['懒人'],
      methods: ['烤'],
      tools: ['空气炸锅'],
    },
    {
      id: 4,
      name: '韭菜炒蛋',
      stuff: ['韭菜', '鸡蛋'],
      difficulty: '简单',
      tags: [],
      methods: ['炒'],
      tools: ['一口大锅'],
    },
  ],
}

describe('lookupShelfLife', () => {
  it('resolves aliases and returns exact days', () => {
    const result = lookupShelfLife(['西红柿,鸡蛋', '菠菜'])
    expect(result.items).toEqual([
      { input: '西红柿', normalized: '番茄', days: 7, isDefault: false },
      { input: '鸡蛋', normalized: '鸡蛋', days: 30, isDefault: false },
      { input: '菠菜', normalized: '菠菜', days: 3, isDefault: false },
    ])
  })

  it('falls back to default days for unknown ingredients', () => {
    const result = lookupShelfLife(['松露'])
    expect(result.items[0]).toEqual({
      input: '松露',
      normalized: '松露',
      days: result.defaultDays,
      isDefault: true,
    })
  })
})

describe('queryCookCatalog', () => {
  it('uses search engine for classic stuff queries', () => {
    const result = queryCookCatalog(catalog, { stuff: ['西红柿', '鸡蛋'] })
    expect(result.engine).toBe('search')
    expect(result.recipes[0]?.name).toBe('番茄炒蛋')
    expect(result.recipes[0]?.videoUrl).toBe('https://www.bilibili.com/video/BV1rf4y1872R')
  })

  it('parses natural language query with noSpicy and tool', () => {
    const result = queryCookCatalog(catalog, {
      query: '冰箱里有土豆，不要辣，用空气炸锅',
    })
    expect(result.engine).toBe('recommend')
    expect(result.parsedQuery?.noSpicy).toBe(true)
    expect(result.parsedQuery?.tool).toBe('空气炸锅')
    expect(result.recipes.map(r => r.name)).toEqual(['空气炸锅烤土豆'])
  })

  it('respects avoid and survival mode', () => {
    const result = queryCookCatalog(catalog, {
      stuff: ['土豆', '鸡蛋', '番茄'],
      avoid: ['番茄'],
      mode: 'survival',
    })
    expect(result.engine).toBe('recommend')
    expect(result.recipes.some(r => r.name === '番茄炒蛋')).toBe(false)
    expect(result.recipes.map(r => r.name)).toEqual(['空气炸锅烤土豆'])
  })

  it('reports incompatible food warnings in recommend mode', () => {
    const result = queryCookCatalog(catalog, {
      stuff: ['牛奶', '韭菜'],
      noSpicy: true,
    })
    expect(result.warnings).toHaveLength(1)
    expect(result.warnings[0]?.foodA).toBe('牛奶')
    expect(result.warnings[0]?.foodB).toBe('韭菜')
  })
})
