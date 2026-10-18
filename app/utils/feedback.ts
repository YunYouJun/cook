import type { FeedbackKind, SearchSignal } from '~/adapters/feedback'

export const feedbackKinds: { kind: FeedbackKind, label: string }[] = [
  { kind: 'video', label: '视频打不开' },
  { kind: 'stuff', label: '食材或厨具不对' },
  { kind: 'info', label: '难度或做法不对' },
  { kind: 'other', label: '其他问题' },
]

const DEVICE_ID_KEY = 'cook:device-id'

/** A random id for deduplicating anonymous feedback; it identifies nothing else. */
export function getDeviceId(): string {
  try {
    const saved = localStorage.getItem(DEVICE_ID_KEY)
    if (saved)
      return saved
    const id = crypto.randomUUID()
    localStorage.setItem(DEVICE_ID_KEY, id)
    return id
  }
  catch {
    return crypto.randomUUID()
  }
}

/** One key per distinct signal, so the same selection is reported once per session. */
export function signalKey(signal: SearchSignal): string {
  return [signal.type, [...signal.stuff].sort().join('、'), signal.tool ?? '', signal.mode ?? '', signal.recipeId ?? ''].join('|')
}
