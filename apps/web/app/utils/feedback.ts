import type { AppFeedbackCategory, FeedbackKind, SearchSignal } from '~/adapters/feedback'
import { appName, links } from '~/constants'

export const feedbackKinds: { kind: FeedbackKind, label: string }[] = [
  { kind: 'video', label: '视频打不开' },
  { kind: 'text', label: '图文链接打不开' },
  { kind: 'stuff', label: '食材或厨具不对' },
  { kind: 'info', label: '难度或做法不对' },
  { kind: 'other', label: '其他问题' },
]

export const linkIssues = [
  { value: '', label: '暂不确定' },
  { value: 'unavailable', label: '内容不存在或已下架' },
  { value: 'loading', label: '一直加载或网络错误' },
  { value: 'login', label: '要求登录或没有访问权限' },
  { value: 'wrong-content', label: '打开的不是这道菜' },
  { value: 'other', label: '其他情况' },
]

/** Only offer source issues for the sources this recipe actually contains. */
export function recipeFeedbackKinds(recipe: { bv?: string, link?: string }) {
  return feedbackKinds.filter(item => item.kind === 'video' ? !!recipe.bv : item.kind === 'text' ? !!recipe.link : true)
}

export const appFeedbackCategories: { category: AppFeedbackCategory, label: string, placeholder: string }[] = [
  { category: 'bug', label: '用着有问题', placeholder: '在哪个页面、做了什么、看到了什么' },
  { category: 'idea', label: '功能建议', placeholder: '想要什么功能，用在什么时候' },
  { category: 'recipe', label: '想要的菜没有', placeholder: '菜名；有 B 站视频的话贴上链接' },
  { category: 'other', label: '其他', placeholder: '说说具体情况' },
]

/** Only the known categories can be preselected by a feedback deep link. */
export function parseAppFeedbackCategory(value: unknown): AppFeedbackCategory {
  return appFeedbackCategories.find(item => item.category === value)?.category ?? 'bug'
}

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

/**
 * A prefilled email to the support mailbox, the fallback when the cloud function
 * can't be reached: no account needed, unlike GitHub. Mirrors `FeedbackClient.mailURL` on iOS.
 */
export function buildFeedbackMail(options: { topic: string, lines: string[], platform?: string }): string {
  const subject = `【${appName}反馈】${options.topic}`
  const body = [
    ...options.lines.filter(Boolean),
    '',
    '---',
    `来自 ${appName}（${options.platform ?? 'web'}）`,
  ].join('\n')
  return `mailto:${links.supportEmail}?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(body)}`
}

/** Keep the same complete message available when a mail app or clipboard is unavailable. */
export function buildFeedbackText(options: { topic: string, lines: string[], platform?: string }): string {
  const mail = new URL(buildFeedbackMail(options))
  return `收件人：${links.supportEmail}\n主题：${mail.searchParams.get('subject')}\n\n${mail.searchParams.get('body')}`
}

/** Best effort: a failed write (no permission, insecure context) just means nothing was copied. */
export async function copyFeedbackText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text)
    return true
  }
  catch {
    return false
  }
}
