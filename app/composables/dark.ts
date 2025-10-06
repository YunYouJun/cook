export const color = useColorMode()
export const isDark = computed(() => color.value === 'dark')

export const ionDarkClass = 'ion-palette-dark'

export function toggleDark() {
  color.preference = color.value === 'dark' ? 'light' : 'dark'
  document.documentElement.classList.toggle(ionDarkClass, !isDark.value)
}
