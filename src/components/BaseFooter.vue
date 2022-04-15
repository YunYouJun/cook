<script lang="ts" setup>
import { isClient } from '@vueuse/core'
const displayICP = ref(true)

onBeforeMount(() => {
  if (isClient)
    displayICP.value = ['cook.yunyoujun.cn', 'localhost'].includes(window.location.hostname)
})
</script>

<template>
  <div p="4" class="flex flex-col justify-center items-center" opacity="70" text="sm">
    <a v-if="displayICP" class="flex" m="1 b-2" href="https://beian.miit.gov.cn/" target="_blank">
      苏ICP备17038157号
    </a>
    <div class="flex justify-center items-center">
      ©️&nbsp;<a href="https://github.com/YunYouJun/cook" target="_blank">Cook</a>
      <div text="xs" m="x-1" i-ri-cloud-line />
      <a href="https://www.yunyoujun.cn" target="_blank">云游君</a>
    </div>
  </div>
</template>
