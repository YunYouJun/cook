<script setup lang="ts">
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import { buildFeedbackMail, buildFeedbackText, copyFeedbackText } from '~/utils/feedback'

const props = defineProps<{ message: { topic: string, lines: string[] } }>()
const copyStatus = shallowRef('')
const text = computed(() => buildFeedbackText(props.message))
const mail = computed(() => buildFeedbackMail(props.message))
watch(text, () => {
  copyStatus.value = ''
})
async function copy() {
  copyStatus.value = await copyFeedbackText(text.value) ? '已复制邮箱和内容。' : '未能自动复制，请选中下方内容手动复制。'
}
</script>

<template>
  <details class="feedback-mail">
    <summary>也可以通过邮件反馈</summary>
    <p>会打开你的邮件应用，需要你确认发送。没有邮件应用时可以复制下方内容；邮件不会生成支持中心回执。</p>
    <div class="feedback-mail-actions">
      <a :href="mail">打开邮件草稿</a>
      <YlfButton type="button" variant="secondary" @click="copy">
        复制邮箱和内容
      </YlfButton>
    </div>
    <p v-if="copyStatus" role="status">
      {{ copyStatus }}
    </p>
    <textarea aria-label="完整邮件反馈内容" :value="text" readonly rows="7" />
  </details>
</template>

<style scoped>
.feedback-mail {
  margin-top: 16px;
  font-size: 13px;
  line-height: 1.8;
}
.feedback-mail summary {
  cursor: pointer;
}
.feedback-mail-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
}
.feedback-mail a {
  color: var(--ylf-c-brand);
}
.feedback-mail textarea {
  width: 100%;
  box-sizing: border-box;
  margin-top: 12px;
  padding: 12px;
  font: inherit;
  color: inherit;
  background: var(--ylf-c-surface);
  border: 1px solid var(--ylf-c-border);
  border-radius: 8px;
}
</style>
