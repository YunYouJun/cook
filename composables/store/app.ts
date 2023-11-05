import { acceptHMRUpdate, defineStore } from 'pinia'
import { useStorage } from '@vueuse/core'
import { defaultSettings } from '~/utils/settings'
import { namespace } from '~/constants'
import { ref } from 'vue'

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
