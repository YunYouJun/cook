<script setup lang="ts">
import type { FeedbackKind, LinkIssue } from '~/adapters/feedback'
import type { RecipeItem } from '~/types'
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import YlfSelect from '@yunlefun/vue/components/YlfSelect.vue'
import { feedbackKinds, linkIssues, recipeFeedbackKinds } from '~/utils/feedback'
import { getRecipeFeedbackLink } from '~/utils/recipe'
import MailFallback from './MailFallback.vue'

const props = defineProps<{ recipe: RecipeItem }>()
const emit = defineEmits<{ close: [] }>()
const feedback = useRecipeFeedback()
const choices = recipeFeedbackKinds(props.recipe).map(item => ({ value: item.kind, label: item.label }))
const kind = ref<FeedbackKind>(choices[0]!.value)
const linkIssue = ref<LinkIssue>('')
const linkOptions = linkIssues.map(item => ({ ...item, value: item.value || 'unspecified' }))
const selectedLinkIssue = computed({
  get: () => linkIssue.value || 'unspecified',
  set: (value: string) => { linkIssue.value = value === 'unspecified' ? '' : value as LinkIssue },
})
const note = ref('')
const pending = ref(false)
const sent = ref(false)
const error = ref('')
const isLink = computed(() => kind.value === 'video' || kind.value === 'text')
const sources = computed(() => [
  { label: '视频做法', url: props.recipe.bv ? `https://www.bilibili.com/video/${props.recipe.bv}` : '' },
  { label: '图文做法', url: props.recipe.link || '' },
].filter(source => source.url))
const mailMessage = computed(() => ({ topic: props.recipe.name, lines: [
  `菜谱：${props.recipe.name}（#${props.recipe.id}）`,
  `问题：${feedbackKinds.find(item => item.kind === kind.value)?.label}`,
  isLink.value && linkIssue.value ? `具体表现：${linkIssues.find(item => item.value === linkIssue.value)?.label}` : '',
  note.value.trim(),
  ...sources.value.map(source => `${source.label}：${source.url}`),
] }))

async function submit() {
  if (pending.value || sent.value)
    return
  if (kind.value === 'other' && !note.value.trim()) {
    error.value = '请补充一下具体问题。'
    return
  }
  pending.value = true
  error.value = ''
  try {
    await feedback.report({ recipeId: props.recipe.id!, recipeName: props.recipe.name, kind: kind.value, linkIssue: isLink.value ? linkIssue.value : '', note: note.value.trim(), sourceBv: props.recipe.bv || '', sourceLink: props.recipe.link || '' })
    sent.value = true
  }
  catch {
    error.value = '暂时没能提交，内容已保留，请重试。'
  }
  finally {
    pending.value = false
  }
}
</script>

<template>
  <ion-modal :is-open="true" :can-dismiss="!pending" @did-dismiss="emit('close')">
    <ion-header>
      <ion-toolbar>
        <ion-title>反馈这道菜</ion-title>
        <ion-buttons slot="end">
          <ion-button :disabled="pending" @click="emit('close')">
            {{ sent ? '完成' : '取消' }}
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <div class="recipe-report">
        <h2>{{ recipe.name }}</h2>
        <p class="muted">
          菜谱 #{{ recipe.id }} · 已自动携带菜名、编号和做法链接
        </p>
        <nav aria-label="本次反馈的做法链接">
          <a v-for="source in sources" :key="source.label" :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.label }} ↗</a>
        </nav>
        <p v-if="feedback.isTest" class="test-notice">
          测试模式：反馈只进入测试队列，不会进入正式审核。
        </p>
        <div v-if="sent" role="status">
          <h3>{{ feedback.isTest ? '测试反馈已收到' : '收到了，谢谢反馈' }}</h3>
          <p>{{ feedback.isTest ? '这是一条测试记录。' : '团队会结合这道菜的做法链接核查。' }}</p>
          <YlfButton @click="emit('close')">
            完成
          </YlfButton>
        </div>
        <form v-else @submit.prevent="submit">
          <fieldset :disabled="pending">
            <label for="recipe-report-kind">问题类型</label>
            <YlfSelect id="recipe-report-kind" v-model="kind" :options="choices" />
            <template v-if="isLink">
              <label for="recipe-report-link-issue">具体表现 <span class="muted">选填</span></label>
              <YlfSelect id="recipe-report-link-issue" v-model="selectedLinkIssue" :options="linkOptions" />
            </template>
            <label for="recipe-report-note">补充说明 <span class="muted">{{ kind === 'other' ? '请填写' : '选填' }}</span></label>
            <textarea id="recipe-report-note" v-model="note" rows="4" maxlength="200" :required="kind === 'other'" placeholder="例如：点开后提示视频已删除，或某项食材写错了" />
            <span class="muted counter">{{ note.length }} / 200</span>
          </fieldset>
          <p class="muted">
            无需登录。会附带当前页面、平台与版本，方便排查；请勿填写密码等敏感信息。
          </p>
          <p v-if="error" role="alert">
            {{ error }}
          </p>
          <YlfButton type="submit" :disabled="pending">
            {{ pending ? '正在提交…' : feedback.isTest ? '提交测试反馈' : '提交反馈' }}
          </YlfButton>
          <template v-if="error && !feedback.isTest">
            <MailFallback :message="mailMessage" />
            <a :href="getRecipeFeedbackLink(recipe)" target="_blank" rel="noopener noreferrer">也可到 GitHub 填写菜谱问题 ↗</a>
          </template>
        </form>
      </div>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.recipe-report {
  max-width: 560px;
  margin: auto;
  padding: 24px;
  color: var(--ylf-c-text);
}
h2 {
  font-size: 24px;
  font-weight: 650;
  margin-bottom: 8px;
}
p {
  margin-block: 12px;
  line-height: 1.7;
}
nav {
  display: flex;
  gap: 20px;
  margin-block: 16px;
}
a {
  color: var(--ylf-c-brand);
}
fieldset {
  border: 0;
  padding: 0;
  margin-block: 24px;
  display: grid;
  gap: 10px;
  min-width: 0;
}
label {
  margin-top: 8px;
  font-weight: 600;
}
.muted {
  font-size: 13px;
  color: var(--ylf-c-text-2);
}
.counter {
  text-align: right;
}
textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 12px;
  border: 1px solid var(--ylf-c-border);
  border-radius: 8px;
  background: var(--ylf-c-surface);
  color: inherit;
  font: inherit;
  font-size: 16px;
  resize: vertical;
}
.test-notice {
  padding: 12px;
  border: 1px solid var(--ylf-c-border);
  border-radius: 8px;
}
</style>
