import { Capacitor } from '@capacitor/core'
import { StatusBar, Style } from '@capacitor/status-bar'
import { ionDarkClass } from '~/constants'

/**
 * 手动切换暗色时，浏览器状态栏颜色跟随应用而不是系统
 */
function syncThemeColor(color: string) {
  document.querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]').forEach(meta => (meta.content = color))
}

export const useDarkMode = createSharedComposable(() => {
  const color = useColorMode()
  const isDark = computed(() => color.value === 'dark')

  async function setLightMode() {
    document.documentElement.classList.remove(ionDarkClass)
    syncThemeColor('#f2f2f6')

    if (Capacitor.isNativePlatform()) {
      await StatusBar.setStyle({ style: Style.Light })
      await StatusBar.setBackgroundColor({ color: '#f2f2f6ff' })
    }
  }

  async function setDarkMode() {
    document.documentElement.classList.add(ionDarkClass)
    syncThemeColor('#000000')

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
