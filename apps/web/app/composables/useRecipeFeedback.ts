import type { AppFeedback, FeedbackReceipt, RecipeReport, SearchSignal } from '~/adapters/feedback'
import { callGuestFeedback, createFeedbackRemote } from '~/adapters/feedback'
import { getDeviceId, signalKey } from '~/utils/feedback'

const sentSignals = new Set<string>()

/**
 * Recipe reports, app feedback and anonymous search signals, sent to the `cook-feedback`
 * cloud function. Signals are best effort and never surface errors.
 */
export function useRecipeFeedback() {
  const auth = useYunlefunAuth()
  const remote = () => createFeedbackRemote(async (name, data) => {
    await auth.initialize()
    return auth.isAuthenticated.value ? auth.callFunction(name, data) : callGuestFeedback(data)
  }, getDeviceId())

  async function report(input: RecipeReport): Promise<void> {
    await remote().report(input)
  }

  function signal(input: SearchSignal): void {
    if (!import.meta.client || !input.stuff.length)
      return
    const key = signalKey(input)
    if (sentSignals.has(key))
      return
    sentSignals.add(key)
    // Fire once per session even if it fails, so an unconfigured backend is not retried on every change.
    remote().signal(input).catch(() => {})
  }

  async function sendFeedback(input: AppFeedback): Promise<FeedbackReceipt> {
    return remote().feedback(input)
  }

  return { report, signal, sendFeedback }
}
