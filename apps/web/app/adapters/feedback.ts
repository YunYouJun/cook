import type { CloudbaseFunctionCaller } from './yunlefunAuth'
import type { FeedbackContext, FeedbackEnvironment } from '~/utils/feedbackContext'

export const FEEDBACK_FUNCTION = 'cook-feedback'

export type FeedbackKind = 'video' | 'text' | 'stuff' | 'info' | 'other'
export type LinkIssue = '' | 'unavailable' | 'loading' | 'login' | 'wrong-content' | 'other'

export interface RecipeReport {
  recipeId: number
  recipeName: string
  kind: FeedbackKind
  note?: string
  linkIssue?: LinkIssue
  /** Recipe sources at submission time; older clients may omit them. */
  sourceBv?: string
  sourceLink?: string
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
  ticketId?: string
  ticketUrl?: string
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

/** Use the existing native HTTP entry for signed-out feedback; never send cookies or a claimed UID. */
export async function callGuestFeedback(data: Record<string, unknown>, fetcher: typeof fetch = fetch): Promise<unknown> {
  const name = data.environment === 'test' ? 'cook-feedback-test' : FEEDBACK_FUNCTION
  const response = await fetcher(`https://api.yunle.fun/${name}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'omit',
    redirect: 'error',
    signal: AbortSignal.timeout(15000),
    body: JSON.stringify(data),
  })
  const result: unknown = await response.json()
  if (!response.ok && (!result || typeof result !== 'object' || !('ok' in result) || result.ok !== false))
    throw new FeedbackError('UNAVAILABLE')
  return result
}

/**
 * Talks to the `cook-feedback` cloud function. Signing in is optional: the
 * function falls back to the random device id to deduplicate and rate limit.
 */
export function createFeedbackRemote(callFunction: CloudbaseFunctionCaller, deviceId: string, metadata?: { environment: FeedbackEnvironment, version: string, context: FeedbackContext }) {
  async function call(data: Record<string, unknown>): Promise<FunctionResult> {
    const result = await callFunction(metadata?.environment === 'test' ? 'cook-feedback-test' : FEEDBACK_FUNCTION, { ...data, ...metadata, deviceId }) as FunctionResult | null
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
      if (typeof value.id !== 'string' || value.productId !== 'cook' || typeof value.createdAt !== 'string' || (value.status !== 'received' && value.status !== 'reviewing' && value.status !== 'resolved'))
        throw new FeedbackError('INVALID_RECEIPT')
      let ticketUrl: string | undefined
      if (typeof value.ticketId === 'string' && typeof value.ticketUrl === 'string') {
        try {
          const url = new URL(value.ticketUrl)
          if (url.origin === 'https://support.yunle.fun' && /^[\w-]{1,128}$/.test(value.ticketId) && url.pathname === `/tickets/${value.ticketId}` && !url.username && !url.password && !url.search)
            ticketUrl = url.toString()
        }
        catch { /* Preserve the valid submission receipt without opening an unsafe URL. */ }
      }
      return { id: value.id, productId: 'cook', createdAt: value.createdAt, status: value.status as FeedbackReceipt['status'], ...(ticketUrl ? { ticketId: String(value.ticketId), ticketUrl } : {}) }
    },
  }
}
