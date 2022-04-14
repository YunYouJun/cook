import { acceptHMRUpdate, defineStore } from 'pinia'

export const useRecipeStore = defineStore('recipe', () => {
  const curStuff = ref(new Set<string>())

  function toggleStuff(name: string) {
    if (curStuff.value.has(name))
      curStuff.value.delete(name)
    else
      curStuff.value.add(name)
  }

  return {
    curStuff,
    toggleStuff,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useRecipeStore, import.meta.hot))
