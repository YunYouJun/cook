import { describe, expect, it } from 'vitest'
import { buildAliasMap, canonicalizeIngredient } from './food-data.js'

describe('buildAliasMap', () => {
  it('包含所有食材标准名', () => {
    const map = buildAliasMap()
    expect(map.get('土豆')).toBe('土豆')
    expect(map.get('鸡蛋')).toBe('鸡蛋')
    expect(map.get('电饭煲')).toBe('电饭煲')
  })

  it('别名映射到标准名', () => {
    const map = buildAliasMap()
    expect(map.get('西红柿')).toBe('番茄')
  })
})

describe('canonicalizeIngredient', () => {
  const aliasMap = buildAliasMap()

  it('标准名不变', () => {
    expect(canonicalizeIngredient('番茄', aliasMap)).toBe('番茄')
  })

  it('别名转为标准名', () => {
    expect(canonicalizeIngredient('西红柿', aliasMap)).toBe('番茄')
  })

  it('未知名称原样返回', () => {
    expect(canonicalizeIngredient('不存在的食材', aliasMap)).toBe('不存在的食材')
  })
})
