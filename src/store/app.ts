import { acceptHMRUpdate, defineStore } from 'pinia'

export const useAppStore = defineStore('app', () => {
  const deferredPrompt = ref<Event | any>()

  return {
    deferredPrompt,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useAppStore, import.meta.hot))
