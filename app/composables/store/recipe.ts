import type { RecommendationMode } from '@yunyoujun/cook'
import type { RecipeItem, StuffItem } from '~/types'
import { StorageSerializers, useStorage } from '@vueuse/core'
import { recommendRecipes, uniqueStuff } from '@yunyoujun/cook/recommend'
import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useIndexedDB } from '~/composables/db'
import { defaultPantry, pantryOptions } from '~/data/pantry'
import { trackCookEvent } from '~/utils/analytics'
import { db } from '../../utils/db'
import { useAppStore } from './app'

const namespace = 'cook'
export type SearchMode = RecommendationMode

export const useRecipeStore = defineStore('recipe', () => {
  const { settings } = useAppStore()
  const indexedDB = useIndexedDB()
  const keyword = shallowRef('')
  const curStuff = settings.keepLocalData
    ? useStorage(`${namespace}:stuff`, new Set<string>(), undefined, { serializer: StorageSerializers.set })
    : ref(new Set<string>())
  const curTool = settings.keepLocalData ? useStorage(`${namespace}:tool`, '') : shallowRef('')
  const curMode = settings.keepLocalData ? useStorage<SearchMode>(`${namespace}:mode`, 'loose') : shallowRef<SearchMode>('loose')
  const pantry = settings.keepLocalData ? useStorage<string[]>(`${namespace}:pantry`, [...defaultPantry]) : ref([...defaultPantry])

  const selectedStuff = computed(() => uniqueStuff([...curStuff.value]))
  const selectedPantry = computed(() => uniqueStuff(pantry.value).filter(item => pantryOptions.includes(item)))

  function toggleStuff(name: string) {
    const names = uniqueStuff([name])
    const next = new Set(selectedStuff.value)
    for (const item of names) {
      if (next.has(item))
        next.delete(item)
      else
        next.add(item)
    }
    curStuff.value = next
  }

  function addStuff(name: string) {
    curStuff.value = new Set(uniqueStuff([...selectedStuff.value, name]))
  }

  function togglePantry(name: string) {
    if (!pantryOptions.includes(name))
      return
    pantry.value = selectedPantry.value.includes(name)
      ? selectedPantry.value.filter(item => item !== name)
      : [...selectedPantry.value, name]
  }

  function clearFilters() {
    keyword.value = ''
    curTool.value = ''
    curMode.value = 'loose'
  }

  function reset() {
    curStuff.value = new Set()
    clearFilters()
  }

  function toggleTools(name: string) {
    curTool.value = curTool.value === name ? '' : name
  }

  function setMode(mode: SearchMode) {
    curMode.value = mode
  }

  const recipes = shallowRef<RecipeItem[]>([])
  const isSearching = shallowRef(true)
  const loadError = shallowRef('')
  let loading: Promise<void> | undefined
  function loadRecipes() {
    if (loading)
      return loading
    isSearching.value = true
    loadError.value = ''
    loading = (async () => {
      try {
        // Await initialization so first visits and saved selections cannot read an empty DB.
        await indexedDB.init()
        recipes.value = await db.recipes.toArray()
      }
      catch {
        loadError.value = '菜谱暂时没有加载成功，请重试。'
      }
      finally {
        isSearching.value = false
        loading = undefined
      }
    })()
    return loading
  }

  const recipeMatches = computed(() => recommendRecipes(recipes.value, {
    stuff: selectedStuff.value,
    pantry: selectedPantry.value,
    tool: curTool.value,
    keyword: keyword.value,
    mode: curMode.value,
  }))
  /** 只选了厨具、还没选食材时，列出用这个厨具能做的菜（保留原来经典视图的用法） */
  const toolRecipes = computed(() => {
    if (selectedStuff.value.length || !curTool.value)
      return []
    const text = keyword.value.trim()
    return recipes.value.filter(recipe => recipe.tools?.includes(curTool.value) && (!text || recipe.name.includes(text)))
  })
  /** 「再补一样」建议：差一种的菜里缺得最多的食材，补上后能直接多做几道 */
  const unlockSuggestions = computed(() => {
    const counts = new Map<string, number>()
    for (const match of recipeMatches.value) {
      const [missing] = match.missingStuff
      if (match.missingStuff.length === 1 && missing)
        counts.set(missing, (counts.get(missing) ?? 0) + 1)
    }
    return [...counts]
      .filter(([, count]) => count >= 2)
      .sort((a, b) => b[1] - a[1])
      .slice(0, 4)
      .map(([name, count]) => ({ name, count }))
  })
  const resultCount = computed(() => selectedStuff.value.length ? recipeMatches.value.length : toolRecipes.value.length)
  const recipesLength = computed(() => recipes.value.length)

  function clickTool(item: StuffItem) {
    toggleTools(item.name)
    trackCookEvent('click_tool')
  }

  onMounted(loadRecipes)

  return {
    recipesLength,
    keyword,
    curTool,
    curMode,
    selectedStuff,
    selectedPantry,
    isSearching,
    loadError,
    loadRecipes,
    clearKeyWord: () => { keyword.value = '' },
    clearFilters,
    toggleStuff,
    togglePantry,
    clearPantry: () => { pantry.value = [] },
    resetPantry: () => { pantry.value = [...defaultPantry] },
    toggleTools,
    reset,
    setMode,
    addStuff,
    recipeMatches,
    toolRecipes,
    unlockSuggestions,
    resultCount,
    clickTool,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useRecipeStore, import.meta.hot))
