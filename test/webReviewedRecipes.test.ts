import type { RecipeItem } from '../apps/web/app/types'
import type { DietaryData, InstitutionalLowGIData, LunchReviewData, ReviewedRecipeCatalog } from '../apps/web/app/utils/reviewed-recipes'
import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import { readFileSync } from 'node:fs'
import { flushPromises, mount } from '@vue/test-utils'
import * as VueUse from '@vueuse/core'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, ref } from 'vue'
import ReviewedRecipeFilters from '../apps/web/app/components/cook/ReviewedRecipeFilters.vue'
import { useRandomRecipe } from '../apps/web/app/composables/recipe'
import { customRecipes } from '../apps/web/app/composables/store/customRecipe'
import { useRecipeStore } from '../apps/web/app/composables/store/recipe'
import { db } from '../apps/web/app/utils/db'
import { buildReviewedRecipeCatalog, reviewedPreparationLink } from '../apps/web/app/utils/reviewed-recipes'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { recommendRecipes } from '../packages/cook/src/utils/recommend'

const read = <T>(path: string): T => JSON.parse(readFileSync(path, 'utf8'))
const recipes = parseRecipeCsv(readFileSync('packages/data/recipe.csv', 'utf8'))
const texts = read<RecipeTextData>('packages/data/recipe-text.json')
const dietary = read<DietaryData>('packages/data/recipe-dietary.json')
const institutional = read<InstitutionalLowGIData>('packages/data/recipe-institutional-low-gi.json')
const lunch = read<LunchReviewData>('specs/dietary-goals/next-day-lunch.json')
const amountsSource = readFileSync('packages/data/recipe-amounts.json', 'utf8')

function build(input: Partial<{
  texts: RecipeTextData
  dietary: DietaryData
  institutional: InstitutionalLowGIData
  lunch: LunchReviewData
}> = {}) {
  return buildReviewedRecipeCatalog({
    recipes,
    texts: input.texts ?? structuredClone(texts),
    dietary: input.dietary ?? structuredClone(dietary),
    institutional: input.institutional ?? structuredClone(institutional),
    lunch: input.lunch ?? structuredClone(lunch),
    amountsSource,
  })
}

const ids = (items: RecipeItem[]) => items.map(recipe => String(recipe.id)).sort((a, b) => Number(a) - Number(b))
const reviewedIDs = new Set([
  ...Object.keys(dietary.recipes),
  ...Object.keys(institutional.recipes),
  ...Object.keys(lunch.recipes),
])
const seededRecipes = recipes.filter(recipe => reviewedIDs.has(String(recipe.id)))
let catalog: ReviewedRecipeCatalog

beforeAll(async () => {
  catalog = await build()
})

beforeEach(async () => {
  setActivePinia(createPinia())
  customRecipes.value = []
  await db.recipes.clear()
  await db.recipes.bulkAdd(seededRecipes)
})

afterEach(() => {
  customRecipes.value = []
})

describe('browser reviewed recipe catalog', () => {
  it('reuses current audit records and exposes the known goal and scene IDs', () => {
    expect(catalog.goals['weight-aware']).toMatchObject({ enabled: true, count: 10 })
    expect(catalog.goals['less-added-sugar']).toMatchObject({ enabled: true, count: 12 })
    expect(catalog.goals['balanced-pairing']).toMatchObject({ enabled: true, count: 36 })
    expect(catalog.scenes['next-day-lunch']).toMatchObject({ enabled: true, count: 10 })
    expect(catalog.scenes['institutional-low-gi']).toMatchObject({ enabled: true, count: 10 })
  })

  it('fails closed for changed versions and stale source fingerprints', async () => {
    const changedVersion = structuredClone(dietary)
    changedVersion.recipes['333'].reviewedAt = '2026-11-08'
    const versionCatalog = await build({ dietary: changedVersion })
    expect(versionCatalog.goals['weight-aware'].enabled).toBe(false)
    expect(Object.keys(versionCatalog.dietaryEntries)).toEqual([])

    const changedTexts = structuredClone(texts)
    changedTexts.recipes['333'].blocks.push({ type: 'paragraph', text: '来源改动' })
    const staleCatalog = await build({ texts: changedTexts })
    expect(staleCatalog.dietaryEntries['333']).toBeUndefined()
    expect(staleCatalog.lunchEntries['333']).toBeUndefined()
    expect(staleCatalog.goals['weight-aware'].enabled).toBe(false)
    expect(staleCatalog.scenes['next-day-lunch'].enabled).toBe(false)

    const changedInstitution = structuredClone(institutional)
    changedInstitution.recipes['881'].sourceURL = changedInstitution.recipes['881'].sourceURL.replace('glycemicindex.com', 'glycemicindex.com.example.org')
    const institutionCatalog = await build({ institutional: changedInstitution })
    expect(institutionCatalog.institutionalEntries['881']).toBeUndefined()
    expect(institutionCatalog.scenes['institutional-low-gi'].enabled).toBe(false)
  })

  it('opens reviewed preparation at the exact imported source commit', async () => {
    expect(reviewedPreparationLink(catalog, 333)).toContain(`/blob/${texts.source.commit}/`)
    expect(reviewedPreparationLink(catalog, 333)).not.toContain('/blob/master/')
    expect(reviewedPreparationLink(catalog, 878)).toBe('')
    const invalidSnapshot = structuredClone(texts)
    invalidSnapshot.source.commit = 'master'
    const invalidCatalog = await build({ texts: invalidSnapshot })
    expect(invalidCatalog.goals['weight-aware'].enabled).toBe(false)
    expect(invalidCatalog.scenes['next-day-lunch'].enabled).toBe(false)
  })

  it('requests review data only when the user opens the optional filters', async () => {
    const wrapper = mount(ReviewedRecipeFilters, {
      props: { goalOptions: [], sceneOptions: [], selectedGoal: null, selectedScene: null, loading: false, loaded: false, unavailableMessage: '' },
    })
    expect(wrapper.emitted('load')).toBeUndefined()
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('load')).toHaveLength(1)
    await wrapper.setProps({ loaded: true })
    await wrapper.get('button').trigger('click')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('load')).toHaveLength(1)
    wrapper.unmount()
  })
})

describe('web reviewed candidate pool', () => {
  it('keeps the ordinary recommendation baseline unchanged when no goal or scene is selected', async () => {
    const store = useRecipeStore()
    await store.loadRecipes()
    store.addStuff('鸡蛋')
    const expected = recommendRecipes(seededRecipes, {
      stuff: ['鸡蛋'],
      pantry: store.selectedPantry,
      avoid: [],
      mode: 'loose',
    })
    expect(store.recipeMatches).toEqual(expected)

    await store.loadReviewedRecipes(async () => catalog)
    expect(store.recipeMatches).toEqual(expected)
  })

  it('browses the exact reviewed goals and scenes without ingredients', async () => {
    const store = useRecipeStore()
    await store.loadReviewedRecipes(async () => catalog)

    store.toggleDietaryGoal('weight-aware')
    expect(ids(store.browseRecipes)).toEqual(['333', '630', '677', '701', '736', '756', '798', '799', '800', '814'])

    store.toggleDietaryGoal('less-added-sugar')
    expect(ids(store.browseRecipes)).toEqual(['333', '630', '677', '689', '696', '800', '805', '829', '844', '846', '850', '851'])

    store.toggleDietaryGoal('balanced-pairing')
    expect(store.browseRecipes).toHaveLength(36)

    store.toggleDietaryGoal('balanced-pairing')
    store.toggleDiscoveryScene('institutional-low-gi')
    expect(ids(store.browseRecipes)).toEqual(['878', '879', '880', '881', '882', '883', '884', '885', '886', '887'])
  })

  it('intersects goal, lunch, ingredients, keyword and empty results without widening', async () => {
    const store = useRecipeStore()
    await store.loadReviewedRecipes(async () => catalog)
    store.toggleDietaryGoal('weight-aware')
    store.toggleDiscoveryScene('next-day-lunch')

    expect(ids(store.browseRecipes)).toEqual(['333', '630', '677', '701', '736', '756', '799', '800', '814'])
    store.addStuff('鸡蛋')
    expect(ids(store.recipeMatches.map(match => match.recipe))).toEqual(['333', '630'])
    store.keyword = '菠菜'
    expect(ids(store.recipeMatches.map(match => match.recipe))).toEqual(['630'])

    store.keyword = ''
    store.reset()
    store.toggleDietaryGoal('weight-aware')
    store.toggleDiscoveryScene('next-day-lunch')
    store.addStuff('火龙果')
    expect(store.recipeMatches).toEqual([])
    expect(store.browseRecipes).toEqual([])
    expect(store.resultCount).toBe(0)
  })

  it('clears one reviewed condition without changing the other or ordinary filters', async () => {
    const store = useRecipeStore()
    await store.loadReviewedRecipes(async () => catalog)
    store.toggleDietaryGoal('weight-aware')
    store.toggleDiscoveryScene('next-day-lunch')
    store.methodGroups = ['炒菜']

    store.toggleDiscoveryScene('next-day-lunch')
    expect(store.dietaryGoal).toBe('weight-aware')
    expect(store.discoveryScene).toBeNull()
    expect(store.methodGroups).toEqual(['炒菜'])

    store.toggleDietaryGoal('weight-aware')
    expect(store.dietaryGoal).toBeNull()
    expect(store.methodGroups).toEqual(['炒菜'])
  })

  it('can clear a selected review filter after its data becomes unavailable', async () => {
    const store = useRecipeStore()
    await store.loadReviewedRecipes(async () => catalog)
    store.toggleDietaryGoal('weight-aware')
    await store.loadReviewedRecipes(async () => {
      throw new Error('offline')
    })
    expect(store.randomPool).toEqual([])
    store.toggleDietaryGoal('weight-aware')
    expect(store.dietaryGoal).toBeNull()
    expect(store.randomPool).toHaveLength(seededRecipes.length)
  })

  it('preserves ordinary random candidates after removing the last tool with a keyword', async () => {
    const store = useRecipeStore()
    await store.loadRecipes()
    store.toggleTools('一口大锅')
    store.keyword = '鸡蛋'
    store.toggleTools('一口大锅')
    expect(store.randomPool).toEqual(seededRecipes)
  })

  it('invalidates displayed random results when candidate filters change', async () => {
    const store = useRecipeStore()
    await store.loadReviewedRecipes(async () => catalog)
    store.toggleDietaryGoal('weight-aware')
    const scope = effectScope()
    try {
      const random = scope.run(() => useRandomRecipe(ref(1)))!
      await random.random()
      expect(random.randomRecipes.value).toHaveLength(1)
      store.keyword = '没有这道菜'
      await flushPromises()
      expect(store.randomPool).toEqual([])
      expect(random.randomRecipes.value).toEqual([])
    }
    finally {
      scope.stop()
    }
  })

  it('keeps reviewed random results out of the ordinary persistent cache', async () => {
    const store = useRecipeStore()
    await store.loadReviewedRecipes(async () => catalog)
    store.toggleDietaryGoal('weight-aware')
    const original = [seededRecipes[0]]
    const saved = ref(original)
    const storage = vi.spyOn(VueUse, 'useStorage').mockReturnValue(saved as ReturnType<typeof VueUse.useStorage>)
    const scope = effectScope()
    try {
      const random = scope.run(() => useRandomRecipe(ref(1)))!
      await random.random()
      expect(random.randomRecipes.value).toHaveLength(1)
      expect(saved.value).toEqual(original)
      expect(random.randomRecipes.value).not.toBe(saved.value)
    }
    finally {
      scope.stop()
      storage.mockRestore()
    }
  })

  it('derives random and private recipe behavior from the same constrained pool', async () => {
    customRecipes.value = [{
      customId: 'private-eggs',
      name: '我的番茄炒蛋',
      stuff: ['番茄', '鸡蛋'],
      tools: ['一口大锅'],
      createdAt: 1,
      updatedAt: 1,
    }]
    const store = useRecipeStore()
    await store.loadReviewedRecipes(async () => catalog)

    store.addStuff('鸡蛋')
    expect(store.recipeMatches.some(match => match.recipe.customId === 'private-eggs')).toBe(true)

    store.toggleDietaryGoal('weight-aware')
    expect(store.recipeMatches.some(match => match.recipe.customId === 'private-eggs')).toBe(false)
    expect(ids(store.randomPool)).toEqual(ids(store.recipeMatches.map(match => match.recipe)))
  })
})
