import { useElementBounding } from '@vueuse/core'
import type { Ref } from 'vue'

/**
 * trigger show invisible element
 * @param target
 * @returns
 */
export function useInvisibleElement(target: Ref<HTMLElement>) {
  const { top } = useElementBounding(target)

  const isVisible = computed(() => {
    return window.scrollY < top.value
  })

  const show = () => {
    window.scrollTo(0, top.value)
  }

  return {
    isVisible,
    show,
  }
}
