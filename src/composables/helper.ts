import { isClient, useElementBounding } from '@vueuse/core'
import type { Ref } from 'vue'

/**
 * trigger show invisible element
 * @param target
 * @returns
 */
export function useInvisibleElement(target: Ref<HTMLElement>) {
  const { top } = useElementBounding(target)

  const isVisible = computed(() => {
    return isClient ? window.scrollY < top.value : true
  })

  const show = () => {
    if (isClient)
      window.scrollTo(0, top.value)
  }

  return {
    isVisible,
    show,
  }
}
