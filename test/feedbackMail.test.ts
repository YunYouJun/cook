import { describe, expect, it } from 'vitest'
import { links } from '../apps/web/app/constants'
import { appFeedbackCategories, buildFeedbackMail } from '../apps/web/app/utils/feedback'

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

describe('appFeedbackCategories', () => {
  it('gives every category its own placeholder', () => {
    expect(appFeedbackCategories.every(item => item.placeholder.length > 0)).toBe(true)
  })
})
