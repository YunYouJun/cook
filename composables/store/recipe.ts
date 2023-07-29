import { acceptHMRUpdate, defineStore } from 'pinia'
import { useStorage } from '@vueuse/core'
import type { RecipeItem, Recipes } from '~/types'

import recipeData from '~/data/recipe.json'
import type { StuffItem } from '~/data/food'

const namespace = 'cook'

/**
 * 生成随机菜谱
 * @param recipes
 * @returns
 */
function generateRandomRecipe(recipes: Recipes) {
  return recipes[Math.floor(Math.random() * recipes.length)]
}

/**
 * survival: 生存模式
 * strict: 严格
 * loose: 模糊
 */
export type SearchMode = 'survival' | 'loose' | 'strict'

export const useRecipeStore = defineStore('recipe', () => {
  const recipes = recipeData as Recipes

  /**
   * 搜索关键字
   */
  const keyword = ref('')

  // can not exported
  const curStuff = useStorage(`${namespace}:stuff`, new Set<string>())
  // const curTools = ref(new Set<string>())
  const curTool = useStorage(`${namespace}:tool`, '')

  const selectedStuff = computed(() => Array.from(curStuff.value))
  // const selectedTools = computed(() => Array.from(curTools.value))
  // const selectedTools = ref('')

  const curMode = useStorage<SearchMode>(`${namespace}:mode`, 'loose')

  function toggleStuff(name: string) {
    if (!curStuff.value)
      return
    if (curStuff.value.has(name))
      curStuff.value.delete(name)
    else
      curStuff.value.add(name)
  }

  function toggleTools(name: string) {
    if (curTool.value === name)
      curTool.value = ''
    else
      curTool.value = name
    // if (curTools.value.has(name))
    //   curTools.value.delete(name)
    // else
    //   curTools.value.add(name)
  }

  function setMode(mode: SearchMode) {
    curMode.value = mode
  }

  /**
   * 重置
   */
  function reset() {
    curStuff.value.clear()
    // curTools.value.clear()
    curTool.value = ''
  }

  function addStuff(name: string) {
    curStuff.value.add(name)
  }

  const randomRecipe = ref<RecipeItem>(generateRandomRecipe(recipes))

  const gtm = useGtm()

  // 默认严格模式
  const displayedRecipe = computed(() => {
    if (keyword.value)
      return recipes.filter(item => item.name.includes(keyword.value))

    if (curMode.value === 'strict') {
      return recipes.filter((item) => {
        const stuffFlag = selectedStuff.value.every(stuff => item.stuff.includes(stuff))
        const toolFlag = item.tools?.includes(curTool.value)
        return curTool.value ? (stuffFlag && toolFlag) : stuffFlag
      })
    }
    else if (curMode.value === 'loose') {
      return recipes.filter((item) => {
        const stuffFlag = selectedStuff.value.some(stuff => item.stuff.includes(stuff))
        const toolFlag = item.tools?.includes(curTool.value)

        // 同时存在 厨具和材料，则同时判断
        if (curTool.value && selectedStuff.value.length) {
          return stuffFlag && toolFlag
        }
        else {
          if (selectedStuff.value.length)
            return stuffFlag
          else if (curTool.value)
            return toolFlag

          return false
        }
      })
    }
    // survival
    else {
      return recipes.filter((item) => {
        const stuffFlag = item.stuff.every(stuff => selectedStuff.value.includes(stuff))
        const toolFlag = item.tools?.includes(curTool.value)
        return curTool.value ? (stuffFlag && toolFlag) : stuffFlag
      })
    }
  })

  /**
   * toggle tool
   * @param item
   */
  const clickTool = (item: StuffItem) => {
    const value = item.name
    toggleTools(value)

    gtm?.trackEvent({
      event: 'click',
      category: `tool_${value}`,
      action: 'click_tool',
      label: '工具',
    })
    gtm?.trackEvent({
      event: 'click_tool',
      action: item.name,
    })
  }

  return {
    recipes,

    keyword,
    curTool,
    curMode,
    selectedStuff,

    randomRecipe,
    random: () => { randomRecipe.value = generateRandomRecipe(recipes) },

    clearKeyWord: () => { keyword.value = '' },
    toggleStuff,
    toggleTools,
    reset,
    setMode,

    addStuff,

    // useRecipe
    displayedRecipe,
    clickTool,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useRecipeStore, import.meta.hot))
