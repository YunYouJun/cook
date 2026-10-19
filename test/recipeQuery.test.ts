import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it } from 'vitest'
import { customRecipes } from '../apps/web/app/composables/store/customRecipe'
import { useRecipeStore } from '../apps/web/app/composables/store/recipe'

describe('recipe store: sentence input', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    customRecipes.value = []
  })

  it('merges what a sentence says into the current filters', () => {
    const store = useRecipeStore()
    store.toggleStuff('鸡蛋')
    store.applyQuery(store.parseText('还有西红柿和洋葱，洋葱不要，不吃辣，用电饭煲做个简单的'))
    expect(store.selectedStuff).toEqual(['鸡蛋', '番茄'])
    expect(store.selectedAvoid).toEqual(['洋葱'])
    expect(store.noSpicy).toBe(true)
    expect(store.curTool).toBe('电饭煲')
    expect(store.difficulty).toEqual(['简单'])

    store.applyQuery(store.parseText('不要鸡蛋'))
    expect(store.selectedStuff).toEqual(['番茄'])
    expect(store.selectedAvoid).toEqual(['洋葱', '鸡蛋'])
  })

  it('recognizes ingredients from my own recipes', () => {
    customRecipes.value = [{ customId: 'custom-a', name: '红烧兔肉', stuff: ['兔肉'], tools: [], createdAt: 1, updatedAt: 1 }]
    const store = useRecipeStore()
    expect(store.parseText('冰箱里有兔肉').stuff).toEqual(['兔肉'])
  })
})
