import type { RecipeView } from '../../utils/settings'
import { useStorage } from '@vueuse/core'
import { acceptHMRUpdate, defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { namespace } from '../../constants'
import { defaultSettings, resolveRecipeView } from '../../utils/settings'

export const useAppStore = defineStore('app', () => {
  const deferredPrompt = ref<Event | any>()
  const settings = useStorage(`${namespace}:settings`, { ...defaultSettings }, undefined, { mergeDefaults: true })
  const recipeView = computed<RecipeView>({
    get: () => resolveRecipeView(settings.value.recipeView),
    set: (view) => { settings.value.recipeView = resolveRecipeView(view) },
  })

  return {
    deferredPrompt,

    settings,
    recipeView,
  }
})

if (import.meta.hot)
  import.meta.hot.accept(acceptHMRUpdate(useAppStore, import.meta.hot))
