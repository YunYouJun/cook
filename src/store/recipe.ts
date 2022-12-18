import { acceptHMRUpdate, defineStore } from 'pinia'

const namespace = 'cook'

/**
 * survival: 生存模式
 * strict: 严格
 * loose: 模糊
 */
export type SearchMode = 'survival' | 'loose' | 'strict'

export const useRecipeStore = defineStore('recipe', () => {
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

  return {
    keyword,
    curTool,
    curMode,
    selectedStuff,

    clearKeyWord: () => { keyword.value = '' },
    toggleStuff,
    toggleTools,
    reset,
    setMode,

    addStuff,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useRecipeStore, import.meta.hot))
