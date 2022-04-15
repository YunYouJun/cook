import { acceptHMRUpdate, defineStore } from 'pinia'

export const useRecipeStore = defineStore('recipe', () => {
  const curStuff = ref(new Set<string>())
  const curTools = ref(new Set<string>())

  const selectedStuff = computed(() => Array.from(curStuff.value))
  const selectedTools = computed(() => Array.from(curTools.value))

  function toggleStuff(name: string) {
    if (!curStuff)
      return
    if (curStuff.value.has(name))
      curStuff.value.delete(name)
    else
      curStuff.value.add(name)
  }

  function toggleTools(name: string) {
    if (!curTools)
      return
    if (curTools.value.has(name))
      curTools.value.delete(name)
    else
      curTools.value.add(name)
  }

  return {
    selectedTools,
    selectedStuff,
    toggleStuff,
    toggleTools,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useRecipeStore, import.meta.hot))
