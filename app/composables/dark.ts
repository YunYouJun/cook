import { Capacitor } from '@capacitor/core'
import { StatusBar, Style } from '@capacitor/status-bar'
import { ionDarkClass } from '~/constants'

export const useDarkMode = createSharedComposable(() => {
  const color = useColorMode()
  const isDark = computed(() => color.value === 'dark')

  async function setLightMode() {
    document.documentElement.classList.remove(ionDarkClass)

    if (Capacitor.isNativePlatform()) {
      await StatusBar.setStyle({ style: Style.Light })
      await StatusBar.setBackgroundColor({ color: '#f2f2f6ff' })
    }
  }

  async function setDarkMode() {
    document.documentElement.classList.add(ionDarkClass)

    if (Capacitor.isNativePlatform()) {
      await StatusBar.setStyle({ style: Style.Dark })
      await StatusBar.setBackgroundColor({ color: '#ff000000' })
    }
  }

  return {
    color,
    isDark,
    setLightMode,
    setDarkMode,

    async toggleDark() {
      const targetMode = color.value === 'dark' ? 'light' : 'dark'
      color.preference = targetMode

      if (targetMode === 'dark') {
        setDarkMode()
      }
      else {
        setLightMode()
      }
    },
  }
})
