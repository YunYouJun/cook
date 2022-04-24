import type { WatchSource } from 'vue'
import { computed, watch } from 'vue'

const setThemeColor = (color: string) => {
  document.head.querySelector('meta[name="theme-color"]')?.setAttribute('content', color)
}

const getThemeColor = () => {
  return document.head.querySelector('meta[name="theme-color"]')?.getAttribute('content') ?? '#ffffff'
}

export const useThemeColor = (isDark: WatchSource<boolean>) => {
  const themeColor = computed({
    get() {
      return getThemeColor()
    },
    set(val: string) {
      setThemeColor(val)
    },
  })

  watch(isDark, (isDark) => {
    if (isDark)
      themeColor.value = '#121212'
    else
      themeColor.value = '#fff'
  }, {
    immediate: true,
    flush: 'post',
  })

  return themeColor
}
