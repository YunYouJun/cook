import { useElementBounding, useIntersectionObserver } from '@vueuse/core'
import type { Ref } from 'vue'
import { ref } from 'vue'

/**
 * trigger show invisible element
 * @param target
 * @returns
 */
export function useInvisibleElement(target: Ref<HTMLElement>) {
  const isVisible = ref(false)
  const { top } = useElementBounding(target)
  useIntersectionObserver(target, ([{ isIntersecting }]) => {
    isVisible.value = isIntersecting
  })

  const show = () => {
    // scroll when collapse is not visible
    if (!isVisible.value)
      window.scrollTo(0, top.value)
  }

  return {
    isVisible,
    show,
  }
}
