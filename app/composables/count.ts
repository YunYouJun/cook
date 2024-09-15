import { useStorage } from '@vueuse/core'

export function useCount() {
  const count = useStorage('count', 5)

  function inc() {
    count.value += 1
  }
  function dec() {
    if (count.value <= 1)
      return
    count.value -= 1
  }

  return {
    count,
    inc,
    dec,
  }
}
