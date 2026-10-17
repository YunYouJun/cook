import type { RecipeItem } from '../app/types'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useAppStore } from '../app/composables/store/app'
import { useRecipeStore } from '../app/composables/store/recipe'
import { db } from '../app/utils/db'

const fixtures: RecipeItem[] = [
  { name: '土豆', stuff: ['土豆'], tools: ['一口大锅'] },
  { name: '腊肠饭', stuff: ['腊肠', '米'], tools: ['电饭煲'] },
  { name: '土豆腊肠饭', stuff: ['土豆', '腊肠', '米'], tools: ['电饭煲'] },
  { name: '盐煎蛋', stuff: ['鸡蛋', '盐'], tools: ['一口大锅'] },
]

beforeEach(async () => {
  setActivePinia(createPinia())
  useAppStore().recipeView = 'recommendation'
  await db.recipes.clear()
  await db.recipes.bulkAdd(fixtures)
})

describe('recipe interaction', () => {
  it('switches between original and recommended results without clearing selection', async () => {
    const app = useAppStore()
    const store = useRecipeStore()
    await db.recipes.add({ name: '缺三种的土豆杂烩', stuff: ['土豆', '米', '鸡蛋', '牛肉'], tools: ['电饭煲'] })
    await store.loadRecipes()
    store.addStuff('土豆')
    expect(store.displayedRecipe.map(r => r.name)).toEqual(['土豆', '土豆腊肠饭'])
    app.recipeView = 'classic'
    expect(store.displayedRecipe.map(r => r.name)).toEqual(['土豆', '土豆腊肠饭', '缺三种的土豆杂烩'])
    app.recipeView = 'recommendation'
    expect(store.selectedStuff).toEqual(['土豆'])
    expect(store.displayedRecipe).toHaveLength(2)
    app.recipeView = 'classic'
    store.toggleStuff('土豆')
    store.curTool = '电饭煲'
    expect(store.displayedRecipe).toHaveLength(3)
    store.curMode = 'strict'
    store.keyword = '腊肠'
    store.reset()
    expect(store.curMode).toBe('strict')
    expect(store.keyword).toBe('腊肠')
  })

  it('normalizes selection, ignores duplicates and toggles canonical names', () => {
    const store = useRecipeStore()
    store.addStuff(' 西红柿 ')
    store.addStuff('番茄')
    store.addStuff(' ')
    expect(store.selectedStuff).toEqual(['番茄'])
    store.toggleStuff('西红柿')
    expect(store.selectedStuff).toEqual([])
    store.toggleTools('电饭煲')
    expect(store.curTool).toBe('电饭煲')
    store.toggleTools('电饭煲')
    expect(store.curTool).toBe('')
  })

  it('loads actual results and reacts immediately to latest filters', async () => {
    const store = useRecipeStore()
    store.addStuff('土豆')
    store.addStuff('腊肠')
    await store.loadRecipes()
    expect(store.displayedRecipe.map(r => r.name)).toEqual(['土豆', '土豆腊肠饭', '腊肠饭'])
    store.curTool = '电饭煲'
    expect(store.displayedRecipe.map(r => r.name)).toEqual(['土豆腊肠饭', '腊肠饭'])
    store.setMode('strict')
    expect(store.displayedRecipe.map(r => r.name)).toEqual(['土豆腊肠饭'])
    store.setMode('survival')
    expect(store.displayedRecipe).toEqual([])
    store.addStuff('米')
    expect(store.displayedRecipe).toHaveLength(2)
    store.keyword = '没有这道菜'
    expect(store.displayedRecipe).toEqual([])
    store.clearFilters()
    expect(store.selectedStuff).toEqual(['土豆', '腊肠', '米'])
    expect(store.displayedRecipe).toHaveLength(3)
  })

  it('updates missing ingredients when pantry changes and preserves pantry on basket reset', async () => {
    const store = useRecipeStore()
    store.addStuff('鸡蛋')
    await store.loadRecipes()
    expect(store.recipeMatches[0]?.missingStuff).toEqual([])
    store.togglePantry('盐')
    expect(store.recipeMatches[0]?.missingStuff).toEqual(['盐'])
    store.togglePantry('蒜')
    store.reset()
    expect(store.recipeMatches).toEqual([])
    expect(store.selectedPantry).toEqual(['油', '蒜'])
    store.clearPantry()
    expect(store.selectedPantry).toEqual([])
    store.resetPantry()
    expect(store.selectedPantry).toEqual(['油', '盐'])
  })

  it('shows load failure, ends loading and can retry', async () => {
    const store = useRecipeStore()
    const read = vi.spyOn(db.recipes, 'toArray').mockRejectedValueOnce(new Error('unavailable'))
    await store.loadRecipes()
    expect(store.loadError).toBeTruthy()
    expect(store.isSearching).toBe(false)
    store.addStuff('土豆')
    await store.loadRecipes()
    expect(store.loadError).toBe('')
    expect(store.displayedRecipe).toHaveLength(2)
    read.mockRestore()
  })
})
