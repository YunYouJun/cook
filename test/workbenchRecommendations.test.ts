import type { RecipeItem } from '../apps/web/app/types'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { useRecipeStore } from '../apps/web/app/composables/store/recipe'
import { db } from '../apps/web/app/utils/db'
import { recommendRecipes } from '../packages/cook/src/utils/recommend'

const fixtures: RecipeItem[] = [
  { id: 1, name: '盐煎蛋', stuff: ['鸡蛋', '盐', '油'], tools: ['一口大锅'], methods: ['煎'], difficulty: '简单' },
  { id: 2, name: '番茄炒蛋', stuff: ['番茄', '鸡蛋'], tools: ['一口大锅'], methods: ['炒'], difficulty: '简单' },
  { id: 3, name: '蛋炒饭', stuff: ['米', '鸡蛋'], tools: ['一口大锅'], methods: ['炒'], difficulty: '普通' },
  { id: 4, name: '资料不全', stuff: [], tools: ['一口大锅'] },
]
beforeEach(async () => {
  setActivePinia(createPinia())
  await db.recipes.clear()
  await db.recipes.bulkAdd(fixtures)
})

describe('workbench reuses the classic recommendation engine', () => {
  it('does not inherit default oil/salt, and does not change classic pantry or selections', async () => {
    const store = useRecipeStore()
    await store.loadRecipes()
    store.addStuff('鸡蛋')
    store.curTool = '一口大锅'
    expect(store.recipeMatches.find(m => m.recipe.name === '盐煎蛋')?.missingStuff).toEqual([])
    expect(store.recommendWithPantry([]).find(m => m.recipe.name === '盐煎蛋')?.missingStuff).toEqual(['盐', '油'])
    expect(store.recommendWithPantry(['盐']).find(m => m.recipe.name === '盐煎蛋')?.missingStuff).toEqual(['油'])
    expect(store.selectedPantry).toEqual(['油', '盐'])
    expect(store.selectedStuff).toEqual(['鸡蛋'])
    expect(store.curTool).toBe('一口大锅')
  })

  it('preserves engine order, missing ingredients and all shared filters', async () => {
    const store = useRecipeStore()
    await store.loadRecipes()
    store.addStuff('番茄、鸡蛋')
    store.curTool = '一口大锅'
    store.methodGroups = ['炒菜']
    store.difficulty = ['简单']
    store.noSpicy = true
    store.keyword = '番茄'
    for (const mode of ['loose', 'strict', 'survival'] as const) {
      store.curMode = mode
      const expected = recommendRecipes(fixtures, {
        stuff: ['番茄', '鸡蛋'],
        tool: '一口大锅',
        pantry: [],
        mode,
        methodGroups: ['炒菜'],
        difficulty: ['简单'],
        noSpicy: true,
        keyword: '番茄',
      })
      expect(store.recommendWithPantry([])).toEqual(expected)
    }
    store.toggleAvoid('鸡蛋')
    expect(store.recommendWithPantry([])).toEqual([])
  })

  it('does not turn unknown records or arbitrary ingredients into recommendations', async () => {
    const store = useRecipeStore()
    await store.loadRecipes()
    store.addStuff('火龙果')
    expect(store.recommendWithPantry(['油', '盐'])).toEqual([])
    store.reset()
    store.addStuff('鸡蛋')
    expect(store.recommendWithPantry([]).some(m => m.recipe.name === '资料不全')).toBe(false)
  })
})
