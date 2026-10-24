export type FeedbackEnvironment = 'production' | 'test'
export type FeedbackEntry = 'recipe-detail' | 'app-feedback' | 'search'

export interface FeedbackContext {
  entryPoint: FeedbackEntry
  origin: string
  path: string
  build: string
  recipeVersion: string
}

export const FEEDBACK_ORIGINS = ['https://cook.yunyoujun.cn', 'https://cook.yunle.fun']

/** Preview hosts and local builds never send to the production inbox, even when signed in. */
export function feedbackEnvironment(origin: string, development: boolean): FeedbackEnvironment {
  return !development && FEEDBACK_ORIGINS.includes(origin) ? 'production' : 'test'
}

export function feedbackContext(entryPoint: FeedbackEntry, location: Pick<Location, 'origin' | 'pathname'>, build: string, recipeVersion: string): FeedbackContext {
  return { entryPoint, origin: location.origin, path: location.pathname.slice(0, 200), build, recipeVersion }
}
