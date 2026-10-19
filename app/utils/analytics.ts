export type CookEventName
  = | 'click_stuff' | 'click_tool' | 'click_dish'
    | 'view_cook' | 'select_ingredients' | 'view_results'
    | 'results_empty' | 'view_recipe_detail' | 'click_recipe_video'

interface ClarityConfig {
  projectId: string
  domains: string
}

type EventTracker = (name: CookEventName) => unknown
let eventTracker: EventTracker | undefined

/** Do not start a replay on login callbacks or URLs carrying credentials. */
export function isPrivateAnalyticsUrl(href: string) {
  try {
    const url = new URL(href)
    const keys = [...url.searchParams.keys(), ...new URLSearchParams(url.hash.slice(1)).keys()]
    return /^\/auth(?:\/|$)/.test(url.pathname)
      || keys.some(key => /^(?:ticket|code|state|token|access_token|refresh_token|id_token|password)$/i.test(key))
  }
  catch {
    return true
  }
}

/** Keep development, previews and native WebViews out of the production project. */
export function resolveClarityConfig(config: ClarityConfig, href: string, development: boolean) {
  const projectId = config.projectId.trim()
  if (development || !/^[a-z\d]{10,}$/i.test(projectId) || isPrivateAnalyticsUrl(href))
    return null
  try {
    const page = new URL(href)
    const domains = config.domains.split(',').map(domain => domain.trim().toLowerCase()).filter(Boolean)
    if (!['https:', 'http:'].includes(page.protocol) || !domains.includes(page.hostname))
      return null
    return { projectId }
  }
  catch {
    return null
  }
}

export function setCookEventTracker(tracker: EventTracker) {
  eventTracker = tracker
}

/** Clarity events carry fixed action names, never recipe names or account data. */
export function trackCookEvent(name: CookEventName) {
  try {
    void Promise.resolve(eventTracker?.(name)).catch(() => undefined)
  }
  catch {
    // Analytics must not interrupt selections or recipe links.
  }
}
