import { useGtm } from '@gtm-support/vue-gtm'
import { useStorage } from '@vueuse/core'
import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, onMounted, ref, watch } from 'vue'
import type { RecipeItem, StuffItem } from '~/types'
import { db } from '../../utils/db'
import { useAppStore } from './app'

const namespace = 'cook'

/**
 * survival: 生存模式
 * strict: 严格
 * loose: 模糊
 */
export type SearchMode = 'survival' | 'loose' | 'strict'

export const useRecipeStore = defineStore('recipe', () => {
  const gtm = useGtm()
  const { settings } = useAppStore()

  /**
   * 搜索关键字
   */
  const keyword = ref('')

  // can not exported
  const curStuff = settings.keepLocalData ? useStorage(`${namespace}:stuff`, new Set<string>()) : ref(new Set<string>())
  // const curTools = ref(new Set<string>())
  const curTool = settings.keepLocalData ? useStorage(`${namespace}:tool`, '') : ref('')
  const curMode = settings.keepLocalData ? useStorage<SearchMode>(`${namespace}:mode`, 'loose') : ref<SearchMode>('loose')

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
   */
  async function searchRecipes() {
    isSearching.value = true
    let result: RecipeItem[] = []

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

    if (keyword.value)
      result = result.filter(item => item.name.includes(keyword.value))

    isSearching.value = false
    return result
  }

  // 默认严格模式
  const displayedRecipe = ref<RecipeItem[]>([])
  // fix curStuff watch
  watch(() => [keyword.value, selectedStuff.value, curTool.value, curMode.value], async () => {
    displayedRecipe.value = [...(await searchRecipes())]
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
