import { isClient } from '@vueuse/core'

export function installPrompt() {
  if (!isClient)
    return

  window.addEventListener('beforeinstallprompt', (e) => {
    // Prevent the mini-infobar from appearing on mobile
    // e.preventDefault()
    // Stash the event so it can be triggered later.
    window.deferredPrompt = e
    // Update UI notify the user they can install the PWA
    // showInstallPromotion()
    // Optionally, send analytics event that PWA install promo was shown.
    console.log('\'beforeinstallprompt\' event was fired.')
  })
}
