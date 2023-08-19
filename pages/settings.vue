<script lang="ts" setup>
const app = useAppStore()

definePageMeta({
  layout: 'child',
})
</script>

<template>
  <div>
    <CommonHeader>
      设置
    </CommonHeader>

    <div
      class="mx-auto max-w-md w-full"
      px-2
      text-left
    >
      <YlfForm>
        <YlfFormItem label="离开网页后保留选中数据">
          <YlfSwitch v-model="app.settings.keepLocalData" />
        </YlfFormItem>
        <YlfFormItem label="更多设置，敬请期待" />
      </YlfForm>
    </div>
  </div>
</template>
