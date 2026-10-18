import type { CloudbaseFunctionCaller } from './yunlefunAuth'

export const FEEDBACK_FUNCTION = 'cook-feedback'

export type FeedbackKind = 'video' | 'stuff' | 'info' | 'other'

export interface RecipeReport {
  recipeId: number
  recipeName: string
  kind: FeedbackKind
  note?: string
}

export interface SearchSignal {
  type: 'empty' | 'open'
  stuff: string[]
  tool?: string
  mode?: string
  recipeId?: number
  rank?: number
}

interface FunctionResult {
  code?: string
  ok?: boolean
}

/**
 * Talks to the `cook-feedback` cloud function. Signing in is optional: the
 * function falls back to the random device id to deduplicate and rate limit.
 */
export function createFeedbackRemote(callFunction: CloudbaseFunctionCaller, deviceId: string) {
  async function call(data: Record<string, unknown>): Promise<void> {
    const result = await callFunction(FEEDBACK_FUNCTION, { ...data, deviceId }) as FunctionResult | null
    if (!result || result.ok !== true)
      throw new Error(`${FEEDBACK_FUNCTION} failed: ${result?.code ?? 'unknown'}`)
  }

  return {
    report: (report: RecipeReport) => call({ action: 'report', ...report }),
    signal: (signal: SearchSignal) => call({ action: 'signal', ...signal }),
  }
}
