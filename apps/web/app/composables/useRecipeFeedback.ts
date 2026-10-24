import type { AppFeedback, FeedbackReceipt, RecipeReport, SearchSignal } from '~/adapters/feedback'
import { callGuestFeedback, createFeedbackRemote } from '~/adapters/feedback'
import { getDeviceId, signalKey } from '~/utils/feedback'
import { feedbackContext, feedbackEnvironment } from '~/utils/feedbackContext'

const sentSignals = new Set<string>()

/**
 * Recipe reports, app feedback and anonymous search signals, sent to the `cook-feedback`
 * cloud function. Signals are best effort and never surface errors.
 */
export function useRecipeFeedback() {
  const auth = useYunlefunAuth()
  const config = useRuntimeConfig().public
  const environment = feedbackEnvironment(import.meta.client ? window.location.origin : '', import.meta.dev)
  const remote = (entryPoint: 'recipe-detail' | 'app-feedback' | 'search') => createFeedbackRemote(async (name, data) => {
    if (environment === 'test')
      return callGuestFeedback(data)
    await auth.initialize()
    return auth.isAuthenticated.value ? auth.callFunction(name, data) : callGuestFeedback(data)
  }, getDeviceId(), { environment, version: config.feedbackVersion, context: feedbackContext(entryPoint, window.location, config.feedbackBuild, config.feedbackRecipeVersion) })

  async function report(input: RecipeReport): Promise<void> {
    await remote('recipe-detail').report(input)
  }

  function signal(input: SearchSignal): void {
    if (!import.meta.client || !input.stuff.length)
      return
    const key = signalKey(input)
    if (sentSignals.has(key))
      return
    sentSignals.add(key)
    // Fire once per session even if it fails, so an unconfigured backend is not retried on every change.
    remote('search').signal(input).catch(() => {})
  }

  async function sendFeedback(input: AppFeedback): Promise<FeedbackReceipt> {
    return remote('app-feedback').feedback(input)
  }

  return { report, signal, sendFeedback, isTest: environment === 'test' }
}
