import type { RecipeItem } from '../../types.js'
import { describe, expect, it } from 'vitest'
import { searchRecipes } from './search.js'

const recipes: RecipeItem[] = [
  { name: '番茄炒蛋', stuff: ['番茄', '鸡蛋'], tools: ['一口大锅'], difficulty: '简单', methods: ['炒'], tags: ['家常菜'] },
  { name: '土豆炖牛肉', stuff: ['土豆', '牛肉', '胡萝卜'], tools: ['电饭煲'], difficulty: '普通', methods: ['炖'], tags: [] },
  { name: '蛋炒饭', stuff: ['鸡蛋', '米'], tools: ['一口大锅'], difficulty: '简单', methods: ['炒'], tags: [] },
  { name: '凉拌黄瓜', stuff: ['黄瓜'], tools: [], difficulty: '简单', methods: ['凉拌'], tags: [] },
]

describe('searchRecipes', () => {
  it('strict: 所选食材全部出现', () => {
    const result = searchRecipes(recipes, { stuff: ['番茄', '鸡蛋'], mode: 'strict' })
    expect(result).toHaveLength(1)
    expect(result[0]!.name).toBe('番茄炒蛋')
  })

  it('strict: 单个食材匹配多个', () => {
    const result = searchRecipes(recipes, { stuff: ['鸡蛋'], mode: 'strict' })
    expect(result).toHaveLength(2)
    expect(result.map(r => r.name)).toContain('番茄炒蛋')
    expect(result.map(r => r.name)).toContain('蛋炒饭')
  })

  it('loose: 任一食材出现即匹配', () => {
    const result = searchRecipes(recipes, { stuff: ['番茄', '黄瓜'], mode: 'loose' })
    expect(result).toHaveLength(2)
    expect(result.map(r => r.name)).toContain('番茄炒蛋')
    expect(result.map(r => r.name)).toContain('凉拌黄瓜')
  })

  it('survival: 菜谱食材全在手头', () => {
    const result = searchRecipes(recipes, { stuff: ['番茄', '鸡蛋', '米', '黄瓜'], mode: 'survival' })
    expect(result.map(r => r.name)).toContain('番茄炒蛋')
    expect(result.map(r => r.name)).toContain('蛋炒饭')
    expect(result.map(r => r.name)).toContain('凉拌黄瓜')
    expect(result.map(r => r.name)).not.toContain('土豆炖牛肉')
  })

  it('tool 过滤', () => {
    const result = searchRecipes(recipes, { stuff: ['鸡蛋'], tool: '电饭煲', mode: 'strict' })
    expect(result).toHaveLength(0)
  })

  it('keyword 过滤', () => {
    const result = searchRecipes(recipes, { stuff: ['鸡蛋'], mode: 'loose', keyword: '炒饭' })
    expect(result).toHaveLength(1)
    expect(result[0]!.name).toBe('蛋炒饭')
  })

  it('仅 keyword 搜索（无 stuff/tool）', () => {
    const result = searchRecipes(recipes, { stuff: [], mode: 'loose', keyword: '炒蛋' })
    expect(result).toHaveLength(1)
    expect(result[0]!.name).toBe('番茄炒蛋')
  })

  it('空 stuff + tool 仅匹配工具', () => {
    const result = searchRecipes(recipes, { stuff: [], tool: '电饭煲', mode: 'loose' })
    expect(result).toHaveLength(1)
    expect(result[0]!.name).toBe('土豆炖牛肉')
  })
})
