import { onMounted, ref, watch } from 'vue'
import { pantryOptions } from '~/data/pantry'

export type CookingStyle = 'classic' | 'workbench'
export const COOKING_STYLE_KEY = 'cook:workbench-preferences'

/** Only read after mount: SSR and the first client render both start in classic mode. */
export function useCookingStyle() {
  const style = ref<CookingStyle>('classic')
  const pantry = ref<string[]>([])
  const canPersist = ref(true)
  let mounted = false

  onMounted(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(COOKING_STYLE_KEY) || 'null')
      if (saved?.style === 'workbench')
        style.value = 'workbench'
      if (Array.isArray(saved?.pantry))
        pantry.value = [...new Set<string>(saved.pantry.filter((name: unknown) => typeof name === 'string' && pantryOptions.includes(name)))]
    }
    catch {
      // Corrupt/blocked storage must never prevent choosing ingredients.
      canPersist.value = false
    }
    mounted = true
  })

  watch([style, pantry], () => {
    if (!mounted)
      return
    try {
      localStorage.setItem(COOKING_STYLE_KEY, JSON.stringify({ style: style.value, pantry: pantry.value }))
      canPersist.value = true
    }
    catch {
      canPersist.value = false
    }
  }, { deep: true })

  return { style, pantry, canPersist }
}
