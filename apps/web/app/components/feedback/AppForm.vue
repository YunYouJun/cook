<script setup lang="ts">
import type { AppFeedbackCategory } from '~/adapters/feedback'
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import YlfInput from '@yunlefun/vue/components/YlfInput.vue'
import YlfSelect from '@yunlefun/vue/components/YlfSelect.vue'
import { useAppFeedbackForm } from '~/composables/useAppFeedbackForm'
import { links } from '~/constants'
import { appFeedbackCategories } from '~/utils/feedback'
import MailFallback from './MailFallback.vue'

const props = withDefaults(defineProps<{ initialCategory?: AppFeedbackCategory }>(), { initialCategory: 'bug' })
const feedback = useRecipeFeedback()
const { category, note, contact, pending, error, receipt, currentCategory, mailMessage, submit } = useAppFeedbackForm(feedback.sendFeedback, props.initialCategory)
const categories = appFeedbackCategories.map(item => ({ value: item.category, label: item.label }))
</script>

<template>
  <section class="cook-feedback-form">
    <template v-if="receipt">
      <div role="status" class="feedback-receipt">
        <span class="receipt-icon i-carbon-checkmark-outline" aria-hidden="true" />
        <h1>收到了，谢谢你。</h1>
        <p>{{ feedback.isTest ? '测试反馈已收到，不会进入正式支持队列。' : '反馈已进入云乐坊支持中心，仅团队可见。' }}</p>
        <p class="receipt-id">
          反馈编号：{{ receipt.id }}
        </p>
        <template v-if="receipt.ticketUrl">
          <YlfButton tag="a" :href="receipt.ticketUrl" rel="noreferrer">
            查看私密工单与回复
          </YlfButton>
          <p>请保存私密链接，不要转发。你可以在工单页补充信息、确认已读；微信通知开通后也可在那里开启。</p>
        </template>
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
      <p v-if="feedback.isTest" role="status">
        测试模式：仅发送到独立测试队列。
      </p>
      <fieldset :disabled="pending" class="feedback-fields">
        <label for="feedback-category">反馈类型</label>
        <YlfSelect id="feedback-category" v-model="category" :options="categories" />
        <label for="feedback-note">具体情况</label>
        <textarea id="feedback-note" v-model="note" maxlength="500" rows="6" required :placeholder="currentCategory.placeholder" />
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
      <MailFallback v-if="error && note.trim() && !feedback.isTest" :message="mailMessage" />
      <p class="feedback-privacy">
        反馈不会自动公开到需求墙。请不要填写密码、验证码或付款信息。
      </p>
    </form>
    <nav class="feedback-links" aria-label="其他反馈入口">
      <a :href="links.wecomKf" target="_blank" rel="noopener noreferrer">
        <span class="i-cook-wechat" aria-hidden="true" />微信客服 ↗
      </a>
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
.feedback-links a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
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
