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

export type AppFeedbackCategory = 'bug' | 'idea' | 'recipe' | 'other'

/** Feedback about the app itself, not one recipe. */
export interface FeedbackReceipt {
  id: string
  productId: 'cook'
  status: 'received' | 'reviewing' | 'resolved'
  createdAt: string
}

export class FeedbackError extends Error {
  constructor(readonly code: string) {
    super(code)
    this.name = 'FeedbackError'
  }
}

export interface AppFeedback {
  category: AppFeedbackCategory
  note: string
  contact?: string
  submissionId?: string
}

interface FunctionResult {
  code?: string
  ok?: boolean
  receipt?: unknown
}

/**
 * Talks to the `cook-feedback` cloud function. Signing in is optional: the
 * function falls back to the random device id to deduplicate and rate limit.
 */
export function createFeedbackRemote(callFunction: CloudbaseFunctionCaller, deviceId: string) {
  async function call(data: Record<string, unknown>): Promise<FunctionResult> {
    const result = await callFunction(FEEDBACK_FUNCTION, { ...data, deviceId }) as FunctionResult | null
    if (!result || result.ok !== true)
      throw new FeedbackError(result?.code ?? 'UNKNOWN_ERROR')
    return result
  }

  return {
    report: (report: RecipeReport) => call({ action: 'report', platform: 'web', ...report }),
    signal: (signal: SearchSignal) => call({ action: 'signal', platform: 'web', ...signal }),
    feedback: async (feedback: AppFeedback): Promise<FeedbackReceipt> => {
      const result = await call({ action: 'feedback', platform: 'web', ...feedback })
      const receipt = result.receipt
      if (!receipt || typeof receipt !== 'object')
        throw new FeedbackError('INVALID_RECEIPT')
      const value = receipt as Record<string, unknown>
      if (typeof value.id !== 'string' || value.productId !== 'cook' || typeof value.createdAt !== 'string' || !['received', 'reviewing', 'resolved'].includes(String(value.status)))
        throw new FeedbackError('INVALID_RECEIPT')
      return { id: value.id, productId: 'cook', createdAt: value.createdAt, status: value.status as FeedbackReceipt['status'] }
    },
  }
}
