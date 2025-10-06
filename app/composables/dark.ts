import { ionDarkClass } from '~/constants'

export const useDarkMode = createSharedComposable(() => {
  const color = useColorMode()
  const isDark = computed(() => color.value === 'dark')

  return {
    color,
    isDark,
    toggleDark() {
      color.preference = color.value === 'dark' ? 'light' : 'dark'
      document.documentElement.classList.toggle(ionDarkClass, !isDark.value)
    },
  }
})
