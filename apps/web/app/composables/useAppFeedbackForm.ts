import type { AppFeedback, AppFeedbackCategory, FeedbackReceipt } from '~/adapters/feedback'
import { computed, shallowRef, watch } from 'vue'
import { FeedbackError } from '~/adapters/feedback'
import { appFeedbackCategories } from '~/utils/feedback'

/** Shared Support form state: failed drafts keep their idempotency key until edited. */
export function useAppFeedbackForm(sendFeedback: (input: AppFeedback) => Promise<FeedbackReceipt>, initialCategory: AppFeedbackCategory = 'bug') {
  const category = shallowRef(initialCategory)
  const note = shallowRef('')
  const contact = shallowRef('')
  const pending = shallowRef(false)
  const error = shallowRef('')
  const receipt = shallowRef<FeedbackReceipt>()
  const submissionId = shallowRef('')
  const currentCategory = computed(() => appFeedbackCategories.find(item => item.category === category.value)!)
  const mailMessage = computed(() => ({
    topic: currentCategory.value.label,
    lines: [note.value.trim(), contact.value.trim() ? `联系方式：${contact.value.trim()}` : ''],
  }))
  watch([category, note, contact], () => {
    submissionId.value = ''
    error.value = ''
  }, { flush: 'sync' })

  async function submit() {
    if (pending.value || receipt.value)
      return
    if (!note.value.trim()) {
      error.value = '先写几句具体情况吧。'
      return
    }
    submissionId.value ||= crypto.randomUUID()
    pending.value = true
    error.value = ''
    try {
      receipt.value = await sendFeedback({ category: category.value, note: note.value.trim(), contact: contact.value.trim() || undefined, submissionId: submissionId.value })
      note.value = ''
      contact.value = ''
    }
    catch (cause) {
      error.value = cause instanceof FeedbackError && cause.code === 'RATE_LIMITED'
        ? '提交得有点快，请稍后再试。内容已经保留。'
        : '暂时没能确认提交结果，内容已经保留。可以重试，或前往支持中心。'
    }
    finally {
      pending.value = false
    }
  }

  return { category, note, contact, pending, error, receipt, currentCategory, mailMessage, submit }
}
