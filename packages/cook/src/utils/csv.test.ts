import type { RecipeItem } from '../types/index.js'
import { describe, expect, it } from 'vitest'
import { RECIPE_CSV_HEADERS } from './config.js'
import { cleanBv, parseRecipeCsv, recipesToCsv } from './csv.js'

describe('cleanBv', () => {
  it('should clean bilibili URL prefix', () => {
    expect(cleanBv('https://www.bilibili.com/video/BV1234567890')).toBe('BV1234567890')
    expect(cleanBv('BV1234567890')).toBe('BV1234567890')
  })

  it('should handle empty or null values', () => {
    expect(cleanBv('')).toBeUndefined()
    expect(cleanBv(null)).toBeUndefined()
    expect(cleanBv(undefined)).toBeUndefined()
    expect(cleanBv('   ')).toBeUndefined()
  })
})

describe('parseRecipeCsv', () => {
  it('should parse valid CSV data', () => {
    const csvData = `${RECIPE_CSV_HEADERS}
宫保鸡丁,鸡胸肉、花生米,BV1234567890,简单,川菜、家常菜,炒、煎,炒锅,
青椒土豆丝,土豆、青椒,,普通,素菜,炒,炒锅,`

    const recipes = parseRecipeCsv(csvData)

    expect(recipes).toHaveLength(2)
    expect(recipes[0]).toEqual({
      name: '宫保鸡丁',
      stuff: ['鸡胸肉', '花生米'],
      bv: 'BV1234567890',
      difficulty: '简单',
      tags: ['川菜', '家常菜'],
      methods: ['炒', '煎'],
      tools: ['炒锅'],
    })
    expect(recipes[1]).toEqual({
      name: '青椒土豆丝',
      stuff: ['土豆', '青椒'],
      bv: undefined,
      difficulty: '普通',
      tags: ['素菜'],
      methods: ['炒'],
      tools: ['炒锅'],
    })
  })

  it('should filter out empty values in arrays', () => {
    const csvData = `${RECIPE_CSV_HEADERS}
测试菜,土豆、、青椒,,,标签1、、标签2,炒、,工具1、,`

    const recipes = parseRecipeCsv(csvData)

    expect(recipes).toHaveLength(1)
    expect(recipes[0]?.stuff).toEqual(['土豆', '青椒'])
    expect(recipes[0]?.tags).toEqual(['标签1', '标签2'])
    expect(recipes[0]?.methods).toEqual(['炒'])
    expect(recipes[0]?.tools).toEqual(['工具1'])
  })

  it('should handle empty arrays correctly', () => {
    const csvData = `${RECIPE_CSV_HEADERS}
简单菜,土豆,,,,,,`

    const recipes = parseRecipeCsv(csvData)

    expect(recipes).toHaveLength(1)
    expect(recipes[0]?.stuff).toEqual(['土豆'])
    expect(recipes[0]?.bv).toBeUndefined()
    expect(recipes[0]?.difficulty).toBeUndefined()
    expect(recipes[0]?.tags).toEqual([])
    expect(recipes[0]?.methods).toEqual([])
    expect(recipes[0]?.tools).toEqual([])
  })

  it('should skip empty lines and lines without name', () => {
    const csvData = `${RECIPE_CSV_HEADERS}

宫保鸡丁,鸡胸肉,BV123,简单,川菜,炒,炒锅,
,土豆,,,,,`

    const recipes = parseRecipeCsv(csvData)

    expect(recipes).toHaveLength(1)
    expect(recipes[0]?.name).toBe('宫保鸡丁')
  })

  it('should throw error for invalid CSV (no data)', () => {
    expect(() => parseRecipeCsv('')).toThrow('No data in csv file')
    expect(() => parseRecipeCsv(RECIPE_CSV_HEADERS)).toThrow('No data in csv file')
  })

  it('should return empty array for mismatched headers', () => {
    const csvData = `wrong,headers,here
宫保鸡丁,鸡胸肉,BV123`

    const recipes = parseRecipeCsv(csvData)

    expect(recipes).toEqual([])
  })

  it('should handle bilibili URLs in bv field', () => {
    const csvData = `${RECIPE_CSV_HEADERS}
测试菜,土豆,https://www.bilibili.com/video/BV1234567890,简单,,,炒锅,`

    const recipes = parseRecipeCsv(csvData)

    expect(recipes[0]?.bv).toBe('BV1234567890')
  })
})

describe('recipesToCsv', () => {
  it('should convert recipes to CSV format', () => {
    const recipes: RecipeItem[] = [
      {
        name: '宫保鸡丁',
        stuff: ['鸡胸肉', '花生米'],
        bv: 'BV1234567890',
        difficulty: '简单',
        tags: ['川菜', '家常菜'],
        methods: ['炒', '煎'],
        tools: ['炒锅'],
      },
      {
        name: '青椒土豆丝',
        stuff: ['土豆', '青椒'],
        tags: [],
        methods: [],
        tools: ['炒锅'],
      },
    ]

    const csv = recipesToCsv(recipes)

    expect(csv).toContain(RECIPE_CSV_HEADERS)
    expect(csv).toContain('宫保鸡丁,鸡胸肉、花生米,BV1234567890,简单,川菜、家常菜,炒、煎,炒锅')
    expect(csv).toContain('青椒土豆丝,土豆、青椒,,,,,炒锅')
  })

  it('should handle empty arrays and undefined values', () => {
    const recipes: RecipeItem[] = [
      {
        name: '简单菜',
        stuff: ['土豆'],
        tags: [],
        methods: [],
        tools: [],
      },
    ]

    const csv = recipesToCsv(recipes)

    expect(csv).toContain('简单菜,土豆,,,,,')
  })

  it('should handle special characters with papaparse (commas, quotes)', () => {
    const recipes: RecipeItem[] = [
      {
        name: '特殊菜品, 含逗号',
        stuff: ['食材A, 含逗号', '食材B'],
        bv: 'BV123',
        difficulty: '简单',
        tags: ['标签"含引号"', '标签2'],
        methods: ['炒'],
        tools: ['工具'],
      },
    ]

    const csv = recipesToCsv(recipes)

    // papaparse 应该自动处理引号和逗号
    expect(csv).toContain(RECIPE_CSV_HEADERS)

    // 解析回去验证数据完整性
    const parsed = parseRecipeCsv(csv)
    expect(parsed).toHaveLength(1)
    expect(parsed[0]).toEqual({
      name: '特殊菜品, 含逗号',
      stuff: ['食材A, 含逗号', '食材B'],
      bv: 'BV123',
      difficulty: '简单',
      tags: ['标签"含引号"', '标签2'],
      methods: ['炒'],
      tools: ['工具'],
    })
  })

  it('should handle newlines in fields with papaparse', () => {
    const recipes: RecipeItem[] = [
      {
        name: '换行\n测试',
        stuff: ['食材1', '食材2'],
        tools: ['工具'],
      },
    ]

    const csv = recipesToCsv(recipes)

    // 解析回去验证数据完整性
    const parsed = parseRecipeCsv(csv)
    expect(parsed).toHaveLength(1)
    expect(parsed[0]?.name).toBe('换行\n测试')
  })
})

describe('cSV roundtrip consistency', () => {
  it('should maintain data consistency through parse and stringify cycle', () => {
    const originalRecipes: RecipeItem[] = [
      {
        name: '宫保鸡丁',
        stuff: ['鸡胸肉', '花生米'],
        bv: 'BV1234567890',
        difficulty: '简单',
        tags: ['川菜', '家常菜'],
        methods: ['炒', '煎'],
        tools: ['炒锅'],
      },
      {
        name: '青椒土豆丝',
        stuff: ['土豆', '青椒'],
        tags: [],
        methods: [],
        tools: ['炒锅'],
      },
    ]

    // Convert to CSV
    const csv = recipesToCsv(originalRecipes)

    // Parse back
    const parsedRecipes = parseRecipeCsv(csv)

    // Should match original data
    expect(parsedRecipes).toEqual(originalRecipes)
  })

  it('should produce same output as fetch command logic', () => {
    // Simulate fetch command output format
    const csvData = `${RECIPE_CSV_HEADERS}
宫保鸡丁,鸡胸肉、花生米,BV1234567890,简单,川菜、家常菜,炒、煎,炒锅,
青椒土豆丝,土豆、青椒,,普通,素菜,炒,炒锅,`

    const recipes = parseRecipeCsv(csvData)

    // Verify output format matches fetch command
    expect(recipes[0]).toMatchObject({
      name: '宫保鸡丁',
      stuff: ['鸡胸肉', '花生米'],
      bv: 'BV1234567890',
      difficulty: '简单',
      tags: ['川菜', '家常菜'],
      methods: ['炒', '煎'],
      tools: ['炒锅'],
    })

    // Verify arrays don't contain empty strings
    expect(recipes[0]?.stuff.every(s => s.length > 0)).toBe(true)
    expect(recipes[0]?.tags?.every(t => t.length > 0)).toBe(true)
  })
})
