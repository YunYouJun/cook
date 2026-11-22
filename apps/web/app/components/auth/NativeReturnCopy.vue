<script setup lang="ts">
import { computed, onMounted, shallowRef, useTemplateRef, watch } from 'vue'
import { cookAndroidIntentUrl, supportsAndroidBrowserIntent } from '~/utils/androidBrowserIntent'
import { nativeReturnUrl } from '~/utils/authInitialization'

const props = defineProps<{ link: string }>()
const copyStatus = shallowRef<'ready' | 'copying' | 'copied' | 'manual'>('ready')
const linkField = useTemplateRef<HTMLTextAreaElement>('linkField')
const supportsIntent = shallowRef(false)

onMounted(() => {
  supportsIntent.value = supportsAndroidBrowserIntent(navigator.userAgent)
})

const androidReturnUrl = computed(() => supportsIntent.value ? nativeReturnUrl(props.link) : undefined)

function openCook() {
  if (androidReturnUrl.value)
    window.location.href = cookAndroidIntentUrl(androidReturnUrl.value)
}

watch(() => props.link, () => {
  copyStatus.value = 'ready'
})

async function copyLink() {
  if (copyStatus.value === 'copying')
    return
  const link = props.link
  copyStatus.value = 'copying'
  let result: 'copied' | 'manual'
  try {
    await navigator.clipboard.writeText(link)
    result = 'copied'
  }
  catch {
    result = 'manual'
  }
  if (props.link === link)
    copyStatus.value = result
}

function selectLink() {
  linkField.value?.focus()
  linkField.value?.select()
}
</script>

<template>
  <ion-item v-if="androidReturnUrl" button detail @click="openCook">
    <ion-label>在云食册中继续</ion-label>
  </ion-item>
  <ion-item button :disabled="copyStatus === 'copying'" @click="copyLink">
    <ion-label>复制回调链接</ion-label>
  </ion-item>
  <ion-item v-if="copyStatus === 'copied'" role="status">
    <ion-label>已复制，请返回云食册粘贴</ion-label>
  </ion-item>
  <ion-item v-if="copyStatus === 'manual'">
    <div class="manual-copy">
      <p role="status">
        请在浏览器中复制下方链接，再返回云食册粘贴。
      </p>
      <textarea
        ref="linkField"
        class="manual-copy-link"
        aria-label="完整回调链接"
        :value="link"
        readonly
        rows="3"
        dir="ltr"
        spellcheck="false"
        autocomplete="off"
      />
      <button class="manual-copy-select" type="button" @click="selectLink">
        全选回调链接
      </button>
    </div>
  </ion-item>
</template>

<style scoped>
.manual-copy {
  width: 100%;
  min-width: 0;
  padding: 12px 0;
}

.manual-copy-link {
  display: block;
  width: 100%;
  margin: 8px 0;
  padding: 8px;
  border: 1px solid var(--ion-color-medium);
  border-radius: 6px;
  background: var(--ion-background-color);
  color: var(--ion-text-color);
  font: inherit;
}

.manual-copy-select {
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--ion-color-medium);
  border-radius: 6px;
  background: var(--ion-background-color);
  color: var(--ion-text-color);
  font: inherit;
  cursor: pointer;
}
</style>
