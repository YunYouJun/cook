import { acceptHMRUpdate, defineStore } from 'pinia'

const namespace = 'cook'

export const useRecipeStore = defineStore('recipe', () => {
  const strict = useStorage(`${namespace}:strict`, false)

  const curStuff = useStorage(`${namespace}:stuff`, new Set<string>())
  // const curTools = ref(new Set<string>())
  const curTool = useStorage(`${namespace}:tool`, '')

  const selectedStuff = computed(() => Array.from(curStuff.value))
  // const selectedTools = computed(() => Array.from(curTools.value))
  // const selectedTools = ref('')

  function toggleStuff(name: string) {
    if (!curStuff)
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

  /**
   * 重置
   */
  function reset() {
    curStuff.value.clear()
    // curTools.value.clear()
    curTool.value = ''
  }

  return {
    strict,
    curTool,
    selectedStuff,
    toggleStuff,
    toggleTools,
    reset,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useRecipeStore, import.meta.hot))
