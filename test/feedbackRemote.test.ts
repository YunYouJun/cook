import { describe, expect, it, vi } from 'vitest'
import { callGuestFeedback, createFeedbackRemote } from '../apps/web/app/adapters/feedback'

const receipt = { id: 'private-test', productId: 'cook', status: 'received', createdAt: '2026-10-23T00:00:00.000Z' }

describe('private app feedback transport', () => {
  it('submits signed-out feedback to the existing HTTP entry without browser credentials', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ok: true, receipt })))
    const remote = createFeedbackRemote((_name, data) => callGuestFeedback(data, fetcher), 'device-test-12345')
    await expect(remote.feedback({ category: 'idea', note: 'test feedback', submissionId: 'same-key-123' })).resolves.toEqual(receipt)
    expect(fetcher).toHaveBeenCalledWith('https://api.yunle.fun/cook-feedback', expect.objectContaining({ credentials: 'omit', redirect: 'error', method: 'POST' }))
    expect(JSON.parse(String(fetcher.mock.calls[0]![1]!.body))).toEqual({ action: 'feedback', platform: 'web', category: 'idea', note: 'test feedback', submissionId: 'same-key-123', deviceId: 'device-test-12345' })
  })

  it('preserves rate-limit errors instead of claiming submission succeeded', async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ ok: false, code: 'RATE_LIMITED' }), { status: 429 }))
    const remote = createFeedbackRemote((_name, data) => callGuestFeedback(data, fetcher), 'device-test-12345')
    await expect(remote.feedback({ category: 'bug', note: 'retry me' })).rejects.toMatchObject({ code: 'RATE_LIMITED' })
  })

  it.each([undefined, { ...receipt, status: ['received'] }])('rejects missing or malformed receipts: %j', async (value) => {
    const remote = createFeedbackRemote(async () => ({ ok: true, receipt: value }), 'device-test-12345')
    await expect(remote.feedback({ category: 'other', note: 'test feedback' })).rejects.toMatchObject({ code: 'INVALID_RECEIPT' })
  })
})
