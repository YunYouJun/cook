import { describe, expect, it, vi } from 'vitest'
import { callGuestFeedback, createFeedbackRemote } from '../apps/web/app/adapters/feedback'
import { feedbackContext, feedbackEnvironment } from '../apps/web/app/utils/feedbackContext'

describe('feedback context and environment routing', () => {
  it('keeps local, preview and development sessions out of production', () => {
    expect(feedbackEnvironment('https://cook.yunyoujun.cn', false)).toBe('production')
    expect(feedbackEnvironment('https://cook.yunle.fun', false)).toBe('production')
    for (const origin of ['http://localhost:3000', 'https://branch.cook.pages.dev', 'https://cook.yunle.fun.evil.test', 'null'])
      expect(feedbackEnvironment(origin, false)).toBe('test')
    expect(feedbackEnvironment('https://cook.yunle.fun', true)).toBe('test')
  })
  it('attaches recipe, issue details and bounded context while using the isolated endpoint', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ok: true })))
    const context = feedbackContext('recipe-detail', { origin: 'http://localhost:3000', pathname: '/tabs/library' }, 'abc123', 'def456')
    const remote = createFeedbackRemote((_name, body) => callGuestFeedback(body, fetcher), 'anonymous-test-device', { environment: 'test', version: '2.0.0', context })
    await remote.report({ recipeId: 708, recipeName: '土豆炖排骨', kind: 'text', linkIssue: 'loading', note: '一直转圈', sourceLink: 'https://example.com/recipe' })
    expect(fetcher.mock.calls[0]![0]).toBe('https://api.yunle.fun/cook-feedback-test')
    expect(JSON.parse(String(fetcher.mock.calls[0]![1]!.body))).toMatchObject({ recipeId: 708, linkIssue: 'loading', note: '一直转圈', context, environment: 'test', version: '2.0.0' })
  })
})
