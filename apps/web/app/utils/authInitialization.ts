interface AuthInitializationScheduler {
  setTimeout: (callback: () => void, delay: number) => number
  clearTimeout: (id: number) => void
  requestAnimationFrame?: (callback: () => void) => number
  cancelAnimationFrame?: (id: number) => void
  requestIdleCallback?: (callback: () => void, options: { timeout: number }) => number
  cancelIdleCallback?: (id: number) => void
}

interface AuthInitializationOptions {
  href: string
  initialize: () => void
  onReady: (callback: () => void) => void
  scheduler: AuthInitializationScheduler
}

export function isAuthCallbackUrl(href: string) {
  return new URL(href, 'https://cook.invalid').pathname.replace(/\/+$/, '') === '/auth/callback'
}

/** Leave the first paint to the recipe UI; authorization callbacks stay immediate. */
export function scheduleAuthInitialization(options: AuthInitializationOptions) {
  const { initialize, onReady, scheduler } = options
  if (isAuthCallbackUrl(options.href)) {
    initialize()
    return () => {}
  }

  let finished = false
  let timer: number | undefined
  let frame: number | undefined
  let idle: number | undefined

  function cancel() {
    finished = true
    if (timer !== undefined)
      scheduler.clearTimeout(timer)
    if (frame !== undefined)
      scheduler.cancelAnimationFrame?.(frame)
    if (idle !== undefined)
      scheduler.cancelIdleCallback?.(idle)
  }

  function run() {
    if (finished)
      return
    cancel()
    initialize()
  }

  // Nuxt readiness also waits for idle time, so start the fallback outside it.
  timer = scheduler.setTimeout(run, 2000)
  onReady(() => {
    if (finished)
      return
    frame = scheduler.requestAnimationFrame?.(() => {
      if (finished)
        return
      frame = scheduler.requestAnimationFrame?.(() => {
        if (finished)
          return
        if (scheduler.requestIdleCallback)
          idle = scheduler.requestIdleCallback(run, { timeout: 1000 })
        else
          run()
      })
    })
  })

  return cancel
}
