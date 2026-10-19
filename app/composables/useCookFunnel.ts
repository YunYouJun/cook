import type { ComputedRef } from 'vue'
import { watch } from 'vue'
import { trackCookEvent } from '~/utils/analytics'

export interface CookFunnelState {
  active: boolean
  hasIngredients: boolean
  results: 'hidden' | 'loading' | 'ready' | 'empty'
}

/** Observe rendered states, so every ingredient entry path shares the same funnel. */
export function useCookFunnel(state: ComputedRef<CookFunnelState>) {
  watch(state, (next, previous) => {
    if (!next.active)
      return
    const entering = !previous?.active
    if (entering)
      trackCookEvent('view_cook')
    if (next.hasIngredients && (entering || !previous?.hasIngredients))
      trackCookEvent('select_ingredients')
    if (entering || next.results !== previous?.results) {
      if (next.results === 'ready')
        trackCookEvent('view_results')
      else if (next.results === 'empty')
        trackCookEvent('results_empty')
    }
  }, { immediate: true, flush: 'post' })
}
