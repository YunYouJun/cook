import { getPrimaryNavigation } from '~/config'
import { scheduleAuthInitialization } from '~/utils/authInitialization'

export default defineNuxtPlugin({
  name: 'cook-auth',
  dependsOn: ['cook-acquisition'],
  setup() {
    const auth = useYunlefunAuth()
    const nuxtApp = useNuxtApp()
    const initialHome = getPrimaryNavigation(window.location.pathname).some(item => item.tab === 'home' && item.active)
    scheduleAuthInitialization({
      href: window.location.href,
      initialize: () => { void auth.initialize() },
      onReady: initialHome
        ? (callback) => { nuxtApp.hooks.hookOnce('cook:home-ready', callback) }
        : onNuxtReady,
      scheduler: window,
    })
  },
})
