import type { Difficulty, RecommendationMode } from '@yunyoujun/cook'
import type { CookQuery } from '@yunyoujun/cook/recommend'
import type { RecipeItem, StuffItem } from '~/types'
import { StorageSerializers, useStorage } from '@vueuse/core'
import { createRecipeRecommender, hasAvoidedStuff, matchesTaste, parseCookQuery, uniqueStuff } from '@yunyoujun/cook/recommend'
import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useIndexedDB } from '~/composables/db'
import { usePersonalCatalog } from '~/composables/usePersonalCatalog'
import { defaultPantry, pantryOptions } from '~/data/pantry'
import { trackCookEvent } from '~/utils/analytics'
import { db } from '../../utils/db'
import { useAppStore } from './app'
import { customRecipes } from './customRecipe'

const namespace = 'cook'
export type SearchMode = RecommendationMode

export const useRecipeStore = defineStore('recipe', () => {
  const personalCatalog = usePersonalCatalog()
  const { settings } = useAppStore()
  const indexedDB = useIndexedDB()
  const keyword = shallowRef('')
  const curStuff = settings.keepLocalData
    ? useStorage(`${namespace}:stuff`, new Set<string>(), undefined, { serializer: StorageSerializers.set })
    : ref(new Set<string>())
  const curTool = settings.keepLocalData ? useStorage(`${namespace}:tool`, '') : shallowRef('')
  const curMode = settings.keepLocalData ? useStorage<SearchMode>(`${namespace}:mode`, 'loose') : shallowRef<SearchMode>('loose')
  const pantry = settings.keepLocalData ? useStorage<string[]>(`${namespace}:pantry`, [...defaultPantry]) : ref([...defaultPantry])
  // 忌口是个人偏好，和常备调料一样跨会话保留，「清空」不会动它
  const avoid = settings.keepLocalData ? useStorage<string[]>(`${namespace}:avoid`, []) : ref<string[]>([])
  const noSpicy = settings.keepLocalData ? useStorage(`${namespace}:no-spicy`, false) : shallowRef(false)
  const methodGroups = settings.keepLocalData ? useStorage<string[]>(`${namespace}:method-groups`, []) : ref<string[]>([])
  const difficulty = settings.keepLocalData ? useStorage<Difficulty[]>(`${namespace}:difficulty`, []) : ref<Difficulty[]>([])

  const selectedStuff = computed(() => uniqueStuff([...curStuff.value]))
  const selectedPantry = computed(() => uniqueStuff(pantry.value).filter(item => pantryOptions.includes(item)))
  const selectedAvoid = computed(() => uniqueStuff(avoid.value))

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

  function toggleAvoid(name: string) {
    avoid.value = selectedAvoid.value.includes(name)
      ? selectedAvoid.value.filter(item => item !== name)
      : [...selectedAvoid.value, name]
  }

  function toggleMethodGroup(label: string) {
    methodGroups.value = methodGroups.value.includes(label)
      ? methodGroups.value.filter(item => item !== label)
      : [...methodGroups.value, label]
  }

  function toggleDifficulty(level: Difficulty) {
    difficulty.value = difficulty.value.includes(level)
      ? difficulty.value.filter(item => item !== level)
      : [...difficulty.value, level]
  }

  /** 整句识别，自己添加的菜谱里用到的食材也要认 */
  function parseText(text: string) {
    return parseCookQuery(text, [...personalCatalog.ingredients.value.map(item => item.name), ...customRecipes.value.flatMap(recipe => recipe.stuff)], personalCatalog.food.value.tools.map(item => item.name))
  }

  /** 把一句话识别出的条件合进当前筛选；忌口和「不辣」与手动勾选一样会一直保留 */
  function applyQuery(query: CookQuery) {
    const avoided = new Set(uniqueStuff(query.avoid))
    curStuff.value = new Set(uniqueStuff([...selectedStuff.value, ...query.stuff, ...query.unknown]).filter(item => !avoided.has(item)))
    if (avoided.size)
      avoid.value = uniqueStuff([...selectedAvoid.value, ...avoided])
    if (query.noSpicy)
      noSpicy.value = true
    if (query.tool)
      curTool.value = query.tool
    methodGroups.value = [...new Set([...methodGroups.value, ...query.methodGroups])]
    difficulty.value = [...new Set([...difficulty.value, ...query.difficulty])]
  }

  /** 忌口、做法、口味、难度这几个对所有列表都生效的条件 */
  function passesPreferences(recipe: RecipeItem) {
    return !hasAvoidedStuff(recipe, selectedAvoid.value)
      && matchesTaste(recipe, { methodGroups: methodGroups.value, noSpicy: noSpicy.value, difficulty: difficulty.value })
  }

  function clearFilters() {
    keyword.value = ''
    curTool.value = ''
    curMode.value = 'loose'
    methodGroups.value = []
    difficulty.value = []
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

  // 自己添加的菜谱排在前面：匹配程度相同时优先推荐
  const allRecipes = computed<RecipeItem[]>(() => [...customRecipes.value, ...recipes.value])

  const recommend = computed(() => createRecipeRecommender(allRecipes.value))
  const recipeMatches = computed(() => recommend.value({
    stuff: selectedStuff.value,
    pantry: selectedPantry.value,
    tool: curTool.value,
    keyword: keyword.value,
    mode: curMode.value,
    avoid: selectedAvoid.value,
    methodGroups: methodGroups.value,
    noSpicy: noSpicy.value,
    difficulty: difficulty.value,
  }))
  /** 只选了厨具、还没选食材时，列出用这个厨具能做的菜（保留原来经典视图的用法） */
  const toolRecipes = computed(() => {
    if (selectedStuff.value.length || !curTool.value)
      return []
    const text = keyword.value.trim()
    return allRecipes.value.filter(recipe => recipe.tools?.includes(curTool.value)
      && (!text || recipe.name.includes(text))
      && passesPreferences(recipe))
  })
  /** 「吃什么」随机的候选：选了食材就从匹配结果里抽，否则按厨具和忌口从全部菜谱里抽 */
  const randomPool = computed(() => {
    if (selectedStuff.value.length)
      return recipeMatches.value.map(match => match.recipe)
    return allRecipes.value.filter(recipe => (!curTool.value || recipe.tools?.includes(curTool.value))
      && passesPreferences(recipe))
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
  const recipesLength = computed(() => allRecipes.value.length)

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
    selectedAvoid,
    methodGroups,
    noSpicy,
    difficulty,
    isSearching,
    loadError,
    loadRecipes,
    clearKeyWord: () => { keyword.value = '' },
    clearFilters,
    toggleStuff,
    togglePantry,
    clearPantry: () => { pantry.value = [] },
    resetPantry: () => { pantry.value = [...defaultPantry] },
    toggleAvoid,
    clearAvoid: () => { avoid.value = [] },
    toggleMethodGroup,
    toggleDifficulty,
    toggleTools,
    reset,
    setMode,
    addStuff,
    parseText,
    applyQuery,
    recipeMatches,
    toolRecipes,
    randomPool,
    unlockSuggestions,
    resultCount,
    clickTool,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useRecipeStore, import.meta.hot))
