import type { MaybeComputedElementRef } from '@vueuse/core'
import { isClient, useElementBounding } from '@vueuse/core'

/**
 * trigger show invisible element
 * @param target
 */
export function useInvisibleElement(target: MaybeComputedElementRef<HTMLElement>) {
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
