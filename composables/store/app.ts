import { acceptHMRUpdate, defineStore } from 'pinia'
import { useStorage } from '@vueuse/core'
import { ref } from 'vue'
import { defaultSettings } from '~/utils/settings'
import { namespace } from '~/constants'

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
