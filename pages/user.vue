<script lang="ts" setup>
import { links } from '~/constants'
</script>

<template>
  <div>
    <CommonHeader>
      我的
    </CommonHeader>

    <div
      class="mx-auto max-w-md w-full"
      px-2
      text-left
    >
      <YlfForm>
        <YlfFormItem icon="i-ri-feedback-line" label="立即反馈" :to="links.feedback" target="_blank" />
        <YlfFormItem icon="i-ri-mail-send-line" label="立即投稿" :to="links.contribute" target="_blank" />
      </YlfForm>

      <YlfForm>
        <YlfFormItem icon="i-ri-settings-line" label="设置" to="/settings" />
      </YlfForm>

      <YlfForm>
        <YlfFormItem icon="i-ri-article-line" label="自定义菜谱" to="/cookbooks/" />
      </YlfForm>

      <YlfForm>
        <YlfFormItem icon="i-ri-question-line" label="帮助" to="/help" />
        <YlfFormItem icon="i-ri-information-line" label="关于" to="/help" />
      </YlfForm>
    </div>

    <BaseFooter mt-4 />
  </div>
</template>
