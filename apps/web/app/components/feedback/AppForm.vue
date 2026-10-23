<script setup lang="ts">
import type { AppFeedbackCategory, FeedbackReceipt } from '~/adapters/feedback'
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import YlfInput from '@yunlefun/vue/components/YlfInput.vue'
import YlfSelect from '@yunlefun/vue/components/YlfSelect.vue'
import { FeedbackError } from '~/adapters/feedback'
import { links } from '~/constants'
import { appFeedbackCategories } from '~/utils/feedback'

const category = shallowRef<AppFeedbackCategory>('bug')
const note = shallowRef('')
const contact = shallowRef('')
const pending = shallowRef(false)
const error = shallowRef('')
const receipt = shallowRef<FeedbackReceipt>()
const submissionId = shallowRef('')
const feedback = useRecipeFeedback()
const categories = appFeedbackCategories.map(item => ({ value: item.category, label: item.label }))
watch([category, note, contact], () => {
  submissionId.value = ''
  error.value = ''
})

async function submit() {
  if (pending.value)
    return
  if (!note.value.trim()) {
    error.value = '先写几句具体情况吧。'
    return
  }
  submissionId.value ||= crypto.randomUUID()
  pending.value = true
  error.value = ''
  try {
    receipt.value = await feedback.sendFeedback({ category: category.value, note: note.value.trim(), contact: contact.value.trim() || undefined, submissionId: submissionId.value })
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
</script>

<template>
  <section class="cook-feedback-form">
    <template v-if="receipt">
      <div role="status" class="feedback-receipt">
        <span class="receipt-icon i-carbon-checkmark-outline" aria-hidden="true" />
        <h1>收到了，谢谢你。</h1>
        <p>反馈已进入云乐坊支持中心，仅团队可见。</p>
        <p class="receipt-id">
          反馈编号：{{ receipt.id }}
        </p>
        <YlfButton variant="secondary" @click="receipt = undefined">
          继续反馈
        </YlfButton>
      </div>
    </template>
    <form v-else @submit.prevent="submit">
      <h1>有什么可以做得更好？</h1>
      <p class="feedback-intro">
        不用登录，也不必离开云食册。问题和建议会私密发送给团队。
      </p>
      <fieldset :disabled="pending" class="feedback-fields">
        <label for="feedback-category">反馈类型</label>
        <YlfSelect id="feedback-category" v-model="category" :options="categories" />
        <label for="feedback-note">具体情况</label>
        <textarea id="feedback-note" v-model="note" maxlength="500" rows="6" required placeholder="遇到了什么问题，或者希望增加什么功能？" />
        <span class="feedback-counter">{{ note.length }} / 500</span>
        <label for="feedback-contact">联系方式 <span>选填</span></label>
        <YlfInput id="feedback-contact" v-model="contact" maxlength="100" placeholder="邮箱或微信，方便我们回复你" />
      </fieldset>
      <p v-if="error" role="alert" class="feedback-error">
        {{ error }}
      </p>
      <YlfButton type="submit" :disabled="pending" class="feedback-send">
        {{ pending ? '正在提交…' : '发送私密反馈' }}
      </YlfButton>
      <p class="feedback-privacy">
        反馈不会自动公开到需求墙。请不要填写密码、验证码或付款信息。
      </p>
    </form>
    <nav class="feedback-links" aria-label="其他反馈入口">
      <a :href="links.support" target="_blank" rel="noopener noreferrer">前往支持中心 ↗</a>
      <a :href="links.feedbackBoard" target="_blank" rel="noopener noreferrer">为产品建议投票 ↗</a>
    </nav>
  </section>
</template>

<style scoped>
.cook-feedback-form {
  max-width: 560px;
  padding: 30px 24px;
  margin: 0 auto;
  color: var(--ylf-c-text);
}
h1 {
  font-size: 25px;
  font-weight: 700;
  line-height: 1.5;
  margin: 0 0 12px;
}
.feedback-intro,
.feedback-receipt p {
  font-size: 14px;
  color: var(--ylf-c-text-2);
  line-height: 1.9;
}
.feedback-fields {
  display: grid;
  gap: 10px;
  padding: 0;
  border: 0;
  margin-top: 28px;
  min-width: 0;
}
.feedback-fields label {
  font-size: 14px;
  font-weight: 650;
  margin-top: 10px;
}
.feedback-fields label span {
  color: var(--ylf-c-text-3);
  font-size: 12px;
  font-weight: 400;
  margin-left: 6px;
}
.feedback-fields textarea {
  width: 100%;
  padding: 12px;
  border: 1px solid var(--ylf-c-border);
  border-radius: var(--ylf-radius-md, 8px);
  background: var(--ylf-c-surface);
  color: var(--ylf-c-text);
  resize: vertical;
  font: inherit;
  font-size: 16px;
  line-height: 1.8;
  box-sizing: border-box;
}
.feedback-fields textarea:focus-visible {
  outline: 2px solid var(--ylf-c-brand);
  outline-offset: 2px;
}
.feedback-counter {
  text-align: right;
  color: var(--ylf-c-text-3);
  font-size: 12px;
}
.feedback-send {
  margin-top: 26px;
  width: 100%;
}
.feedback-privacy {
  margin-top: 14px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--ylf-c-text-3);
}
.feedback-error {
  padding: 12px;
  background: var(--ylf-c-bg-soft);
  border-radius: 8px;
  line-height: 1.8;
  font-size: 13px;
}
.feedback-links {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 16px;
  border-top: 1px solid var(--ylf-c-border);
  margin-top: 30px;
  padding-top: 20px;
  font-size: 13px;
  color: var(--ylf-c-brand);
}
.feedback-receipt {
  padding-block: 30px;
  text-align: center;
}
.feedback-receipt .receipt-id {
  font-size: 11px;
  overflow-wrap: anywhere;
  margin-block: 20px;
}
.receipt-icon {
  display: inline-block;
  width: 48px;
  height: 48px;
  color: var(--ylf-c-brand);
  margin-bottom: 20px;
}
</style>
