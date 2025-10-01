export const color = useColorMode()
export const isDark = computed(() => color.value === 'dark')

export function toggleDark() {
  color.preference = color.value === 'dark' ? 'light' : 'dark'
  document.documentElement.classList.toggle('ion-palette-dark', !isDark.value)
}
