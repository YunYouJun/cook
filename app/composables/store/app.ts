import { useStorage } from '@vueuse/core'
import { acceptHMRUpdate, defineStore } from 'pinia'
import { ref } from 'vue'
import { namespace } from '../../constants'
import { defaultSettings } from '../../utils/settings'

export const useAppStore = defineStore('app', () => {
  const deferredPrompt = ref<Event | any>()
  const settings = useStorage(`${namespace}:settings`, defaultSettings)

  return {
    deferredPrompt,

    settings,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useAppStore, import.meta.hot))
