import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { FeedbackError } from '../apps/web/app/adapters/feedback'
import { useAppFeedbackForm } from '../apps/web/app/composables/useAppFeedbackForm'
import { parseAppFeedbackCategory } from '../apps/web/app/utils/feedback'

const receipt = { id: 'support-123', productId: 'cook' as const, status: 'received' as const, createdAt: '2026-10-24T00:00:00Z' }
const scopes: ReturnType<typeof effectScope>[] = []
function setup(initial: 'bug' | 'recipe' | 'idea' = 'bug') {
  const send = vi.fn().mockResolvedValue(receipt)
  const scope = effectScope()
  scopes.push(scope)
  return { send, form: scope.run(() => useAppFeedbackForm(send, initial))! }
}
afterEach(() => {
  scopes.splice(0).forEach(scope => scope.stop())
})

describe('support app feedback form', () => {
  it('preselects recipe submissions and preserves complete failed drafts for retry and email', async () => {
    const { send, form } = setup('recipe')
    send.mockRejectedValueOnce(new Error('offline'))
    form.note.value = '  番茄 & 鸡蛋 + 葱  '
    form.contact.value = ' cook+qa@example.com '
    await form.submit()
    expect(form.receipt.value).toBeUndefined()
    expect(form.error.value).toContain('支持中心')
    expect(form.note.value).toBe('  番茄 & 鸡蛋 + 葱  ')
    expect(form.contact.value).toBe(' cook+qa@example.com ')
    expect(form.mailMessage.value).toEqual({ topic: '想要的菜没有', lines: ['番茄 & 鸡蛋 + 葱', '联系方式：cook+qa@example.com'] })
    const firstSubmission = send.mock.calls[0][0]
    expect(firstSubmission).toEqual({ category: 'recipe', note: '番茄 & 鸡蛋 + 葱', contact: 'cook+qa@example.com', submissionId: expect.any(String) })
    await form.submit()
    expect(send.mock.calls[1][0]).toEqual(firstSubmission)
    expect(form.receipt.value).toEqual(receipt)
    expect(form.note.value).toBe('')
    expect(form.contact.value).toBe('')
    expect(form.error.value).toBe('')
  })

  it.each(['note', 'contact', 'category'] as const)('uses a new retry key after editing %s', async (field) => {
    const { send, form } = setup()
    send.mockRejectedValue(new FeedbackError('RATE_LIMITED'))
    form.note.value = '初次反馈'
    await form.submit()
    expect(form.error.value).toContain('稍后再试')
    if (field === 'category')
      form.category.value = 'idea'
    else
      form[field].value = '新内容'
    await form.submit()
    expect(send.mock.calls[1][0].submissionId).not.toBe(send.mock.calls[0][0].submissionId)
  })

  it('blocks empty drafts, repeated clicks while sending and resubmission of an accepted receipt', async () => {
    const { send, form } = setup('idea')
    form.note.value = '   '
    await form.submit()
    expect(send).not.toHaveBeenCalled()
    form.note.value = '希望能按季节推荐'
    let finish!: (value: typeof receipt) => void
    send.mockReturnValueOnce(new Promise((resolve) => {
      finish = resolve
    }))
    const first = form.submit()
    expect(form.pending.value).toBe(true)
    await form.submit()
    expect(send).toHaveBeenCalledTimes(1)
    finish(receipt)
    await first
    expect(form.pending.value).toBe(false)
    await form.submit()
    expect(send).toHaveBeenCalledTimes(1)
  })

  it('accepts only supported deep-link categories', () => {
    expect(parseAppFeedbackCategory('recipe')).toBe('recipe')
    expect(parseAppFeedbackCategory('idea')).toBe('idea')
    for (const value of [undefined, null, ['recipe'], 'invalid', '<script>'])
      expect(parseAppFeedbackCategory(value)).toBe('bug')
  })
})
