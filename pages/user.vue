<script lang="ts" setup>
import { links } from '~/constants'

const app = useAppStore()
</script>

<template>
  <div>
    <CommonHeader>
      我的
    </CommonHeader>

    <div
      class="mx-auto max-w-md w-full"
      text-left
    >
      <YlfForm>
        <YlfFormItem icon="i-ri-feedback-line" label="立即反馈" :to="links.feedback" target="_blank" />
        <YlfFormItem icon="i-ri-mail-send-line" label="立即投稿" :to="links.contribute" target="_blank" />
      </YlfForm>

      <YlfForm>
        <YlfFormItem label="离开网页后保留选中数据">
          <YlfSwitch v-model="app.settings.keepLocalData" />
        </YlfFormItem>
        <YlfFormItem label="更多设置，敬请期待" />
      </YlfForm>

      <YlfForm>
        <YlfFormItem label="关于" to="/help" />
      </YlfForm>
    </div>

    <BaseFooter mt-4 />
  </div>
</template>
