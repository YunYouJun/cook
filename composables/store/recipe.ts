import { acceptHMRUpdate, defineStore } from 'pinia'
import { useStorage } from '@vueuse/core'
import { computed, onMounted, ref, watch } from 'vue'
import { useGtm } from '@gtm-support/vue-gtm'
import type { RecipeItem } from 'types'
import type { StuffItem } from '../../data/food'
import { db } from '../../utils/db'

const namespace = 'cook'

/**
 * survival: 生存模式
 * strict: 严格
 * loose: 模糊
 */
export type SearchMode = 'survival' | 'loose' | 'strict'

export const useRecipeStore = defineStore('recipe', () => {
  const gtm = useGtm()

  /**
   * 搜索关键字
   */
  const keyword = ref('')

  // can not exported
  const curStuff = useStorage(`${namespace}:stuff`, new Set<string>())
  // const curTools = ref(new Set<string>())
  const curTool = useStorage(`${namespace}:tool`, '')
  const curMode = useStorage<SearchMode>(`${namespace}:mode`, 'loose')

  const selectedStuff = computed(() => Array.from(curStuff.value))
  // const selectedTools = computed(() => Array.from(curTools.value))
  // const selectedTools = ref('')

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

  const isSearching = ref(false)
  /**
   * 搜索菜谱
   * @returns
   */
  async function searchRecipes() {
    isSearching.value = true
    let result: RecipeItem[] = []
    if (keyword.value)
      result = await db.recipes.filter(item => item.name.includes(keyword.value)).toArray()

    if (curMode.value === 'strict') {
      result = await db.recipes.filter((item) => {
        const stuffFlag = selectedStuff.value.every(stuff => item.stuff.includes(stuff))
        const toolFlag = item.tools.includes(curTool.value)
        return curTool.value ? (stuffFlag && toolFlag) : stuffFlag
      }).toArray()
    }
    else if (curMode.value === 'loose') {
      result = await db.recipes.filter((item) => {
        const stuffFlag = selectedStuff.value.some(stuff => item.stuff.includes(stuff))
        const toolFlag = Boolean(item.tools?.includes(curTool.value))

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
      }).toArray()
    }
    // survival
    else {
      result = await db.recipes.filter((item) => {
        const stuffFlag = item.stuff.every(stuff => selectedStuff.value.includes(stuff))
        const toolFlag = item.tools?.includes(curTool.value)
        return Boolean(curTool.value ? (stuffFlag && toolFlag) : stuffFlag)
      }).toArray()
    }

    isSearching.value = false
    return result
  }

  // 默认严格模式
  const displayedRecipe = ref<RecipeItem[]>([])
  watch([keyword, curStuff, curTool, curMode], async () => {
    displayedRecipe.value = await searchRecipes()
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

  const recipesLength = ref(0)
  onMounted(async () => {
    db.recipes.count().then((count) => {
      recipesLength.value = count
    })

    displayedRecipe.value = await searchRecipes()
  })

  return {
    recipesLength,

    keyword,
    curTool,
    curMode,
    selectedStuff,

    isSearching,

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
