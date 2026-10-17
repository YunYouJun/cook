import type { RecipeItem } from '../types/recipe'
import { describe, expect, it } from 'vitest'
import { recommendRecipes, uniqueStuff } from './recommend'

const recipe = (name: string, stuff: string[], tools = ['一口大锅']): RecipeItem => ({ name, stuff, tools })

describe('ingredient recommendations', () => {
  it('ranks missing count first, selected coverage next, then original order', () => {
    const recipes = [
      recipe('缺两种但用得多', ['番茄', '鸡蛋', '土豆', '牛肉', '米']),
      recipe('齐全但只用一种', ['土豆']),
      recipe('缺一种', ['番茄', '鸡蛋', '米']),
      recipe('齐全且用两种，先收录', ['番茄', '鸡蛋']),
      recipe('齐全且用两种，后收录', ['土豆', '鸡蛋']),
    ]
    const original = structuredClone(recipes)
    const matches = recommendRecipes(recipes, { stuff: ['番茄', '鸡蛋', '土豆'] })
    expect(matches.map(item => item.recipe.name)).toEqual([
      '齐全且用两种，先收录',
      '齐全且用两种，后收录',
      '齐全但只用一种',
      '缺一种',
      '缺两种但用得多',
    ])
    expect(recipes).toEqual(original)
  })

  it('deduplicates aliases, whitespace and recorded separators on both sides', () => {
    const matches = recommendRecipes([recipe('重复记录', ['番茄，鸡蛋', '鸡蛋', '土豆', '土豆', ' '])], {
      stuff: [' 西红柿 ', '番茄', '鸡蛋', '鸡蛋', ''],
    })
    expect(matches[0]).toMatchObject({
      requiredStuff: ['番茄', '鸡蛋', '土豆'],
      matchedStuff: ['番茄', '鸡蛋'],
      missingStuff: ['土豆'],
    })
    expect(uniqueStuff(['米饭', '米', '虾仁、虾'])).toEqual(['米', '虾'])
  })

  it('uses only recorded requirements, never infers ingredients from a dish name', () => {
    const matches = recommendRecipes([recipe('蒜香黄油鸡翅', ['鸡肉'])], { stuff: ['鸡肉'], pantry: [] })
    expect(matches[0]?.missingStuff).toEqual([])
    expect(matches[0]?.requiredStuff).toEqual(['鸡肉'])
  })

  it('counts pantry only when recorded and reports its contribution separately', () => {
    const recipes = [recipe('盐煎蛋', ['鸡蛋', '盐', '盐'])]
    expect(recommendRecipes(recipes, { stuff: ['鸡蛋'] })[0]?.missingStuff).toEqual(['盐'])
    expect(recommendRecipes(recipes, { stuff: ['鸡蛋'], pantry: ['盐', '盐', '油'] })[0]).toMatchObject({
      matchedStuff: ['鸡蛋'],
      pantryStuff: ['盐'],
      missingStuff: [],
      requiredStuff: ['鸡蛋', '盐'],
    })
    expect(recommendRecipes(recipes, { stuff: ['鸡蛋', '盐'], pantry: ['盐'] })[0]?.pantryStuff).toEqual([])
  })

  it('does not boost ranking with pantry items or match an unrelated recipe via pantry', () => {
    const recipes = [recipe('先收录', ['鸡蛋']), recipe('常备多', ['鸡蛋', '盐', '油']), recipe('无关', ['米', '盐'])]
    expect(recommendRecipes(recipes, { stuff: ['鸡蛋'], pantry: ['盐', '油'] }).map(m => m.recipe.name)).toEqual(['先收录', '常备多'])
  })

  it('requires a selected ingredient and omits unknown or distant matches', () => {
    const recipes = [recipe('空记录', []), recipe('空白记录', [' ']), recipe('普通', ['鸡蛋']), recipe('差三种', ['鸡蛋', '土豆', '米', '番茄'])]
    expect(recommendRecipes(recipes, { stuff: [], pantry: ['鸡蛋'], tool: '一口大锅' })).toEqual([])
    expect(recommendRecipes(recipes, { stuff: ['火龙果'] })).toEqual([])
    expect(recommendRecipes(recipes, { stuff: ['鸡蛋'] }).map(m => m.recipe.name)).toEqual(['普通'])
  })

  it('combines tool, trimmed keyword and explicit matching modes', () => {
    const recipes = [recipe('番茄炒蛋', ['番茄', '鸡蛋']), recipe('番茄蛋饭', ['番茄', '鸡蛋', '米'], ['电饭煲']), recipe('煎蛋', ['鸡蛋'])]
    expect(recommendRecipes(recipes, { stuff: ['番茄', '鸡蛋'], mode: 'strict' }).map(m => m.recipe.name)).toEqual(['番茄炒蛋', '番茄蛋饭'])
    expect(recommendRecipes(recipes, { stuff: ['番茄', '鸡蛋'], mode: 'survival' }).map(m => m.recipe.name)).toEqual(['番茄炒蛋', '煎蛋'])
    expect(recommendRecipes(recipes, { stuff: ['番茄'], tool: '电饭煲', keyword: ' 蛋饭 ' })[0]?.missingStuff).toEqual(['鸡蛋', '米'])
    expect(recommendRecipes(recipes, { stuff: ['番茄'], keyword: '不存在' })).toEqual([])
  })
})
