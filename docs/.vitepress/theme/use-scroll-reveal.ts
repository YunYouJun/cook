import type { ReadonlyRefOrGetter } from 'vue'
import { onBeforeUnmount, onMounted, toValue } from 'vue'

/**
 * Progressively reveals `[data-reveal]` elements as they enter the viewport.
 * Uses a `data-revealed` attribute instead of a CSS class so Vue's reactive `:class`
 * bindings on child roots never clobber revealed state.
 */
export function useScrollReveal(container: ReadonlyRefOrGetter<HTMLElement | null>) {
  let observer: IntersectionObserver | undefined
  let motionQuery: MediaQueryList | undefined

  function revealAll(root: HTMLElement) {
    root.classList.remove('reveal-ready')
    for (const el of root.querySelectorAll<HTMLElement>('[data-reveal]'))
      el.setAttribute('data-revealed', 'true')
    observer?.disconnect()
  }

  function onMotionChange() {
    const root = toValue(container)
    if (root && motionQuery?.matches)
      revealAll(root)
  }

  onMounted(() => {
    const root = toValue(container)
    if (!root)
      return

    motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
    motionQuery.addEventListener('change', onMotionChange)

    if (motionQuery.matches || typeof IntersectionObserver === 'undefined') {
      revealAll(root)
      return
    }

    const targets = [...root.querySelectorAll<HTMLElement>('[data-reveal]')]
    if (!targets.length)
      return

    const viewportCutoff = window.innerHeight * 0.94
    for (const el of targets) {
      if (el.getBoundingClientRect().top <= viewportCutoff)
        el.setAttribute('data-revealed', 'true')
    }

    root.classList.add('reveal-ready')

    observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting)
          continue
        entry.target.setAttribute('data-revealed', 'true')
        observer?.unobserve(entry.target)
      }
    }, { threshold: 0.08, rootMargin: '0px 0px -32px 0px' })

    for (const el of targets) {
      if (!el.hasAttribute('data-revealed'))
        observer.observe(el)
    }
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    motionQuery?.removeEventListener('change', onMotionChange)
  })
}
