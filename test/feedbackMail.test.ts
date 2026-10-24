import { afterEach, describe, expect, it, vi } from 'vitest'
import { links } from '../apps/web/app/constants'
import { appFeedbackCategories, buildFeedbackMail, buildFeedbackText, copyFeedbackText } from '../apps/web/app/utils/feedback'

afterEach(() => vi.unstubAllGlobals())

describe('buildFeedbackMail', () => {
  it('prefills subject and body for the support mailbox, skipping empty lines', () => {
    const url = new URL(buildFeedbackMail({ topic: '功能建议', lines: ['想要减脂模式', ''] }))
    expect(url.protocol).toBe('mailto:')
    expect(url.pathname).toBe(links.supportEmail)
    expect(url.searchParams.get('subject')).toBe('【食用手册反馈】功能建议')
    expect(url.searchParams.get('body')).toBe('想要减脂模式\n\n---\n来自 食用手册（web）')
  })

  it('encodes spaces as %20 so mail apps do not show plus signs', () => {
    const mail = buildFeedbackMail({ topic: 'a b', lines: ['c & d'] })
    expect(mail).not.toContain('+')
    expect(mail).toContain('a%20b')
  })
})

describe('feedback fallback text', () => {
  it('preserves the recipient, category, contact and platform without a mail app', () => {
    const text = buildFeedbackText({ topic: '想要的菜没有', lines: ['番茄 & 鸡蛋 + 葱', '联系方式：cook+test@example.com'] })
    expect(text).toBe('收件人：kf@yunle.fun\n主题：【食用手册反馈】想要的菜没有\n\n番茄 & 鸡蛋 + 葱\n联系方式：cook+test@example.com\n\n---\n来自 食用手册（web）')
  })

  it('reports unavailable or denied clipboard access without losing the fallback', async () => {
    vi.stubGlobal('navigator', {})
    expect(await copyFeedbackText('反馈')).toBe(false)
    const writeText = vi.fn().mockRejectedValue(new Error('Permission denied'))
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    expect(await copyFeedbackText('反馈')).toBe(false)
    writeText.mockResolvedValue(undefined)
    expect(await copyFeedbackText('反馈')).toBe(true)
    expect(writeText).toHaveBeenLastCalledWith('反馈')
  })
})

describe('appFeedbackCategories', () => {
  it('gives every category its own placeholder', () => {
    expect(appFeedbackCategories.every(item => item.placeholder.length > 0)).toBe(true)
  })
})
