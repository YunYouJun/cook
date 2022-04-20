<script lang="ts" setup>
import { isClient } from '@vueuse/core'
const displayICP = ref(true)

onBeforeMount(() => {
  if (isClient)
    displayICP.value = ['cook.yunyoujun.cn', 'localhost'].includes(window.location.hostname)
})
</script>

<template>
  <div p="4" class="flex flex-col justify-center items-center" text="sm">
    <a v-if="displayICP" opacity="80" class="flex" href="https://beian.miit.gov.cn/" target="_blank">
      苏ICP备17038157号
    </a>
    <div m="t-2" class="inline-flex justify-center items-center" text="xs">
      <a class="inline-flex justify-center items-center" style="color: #ea7b99" href="https://www.bilibili.com/blackboard/dynamic/306882" target="_blank">
        <span inline-flex>菜谱视频来源：</span>
        <div class="inline-flex" i-ri-bilibili-line />
        <span m="l-1" class="inline-flex" style="margin-top: 1px;">B 站</span>
      </a>
    </div>
    <div m="t-2" opacity="80" class="flex justify-center items-center">
      ©️&nbsp;<a href="https://github.com/YunYouJun/cook" target="_blank">Cook</a>
      <div text="xs" m="x-1" i-ri-cloud-line />
      <a href="https://www.yunyoujun.cn" target="_blank">云游君</a>
    </div>
    <div m="t-2" opacity="80" class="footer-support flex justify-center items-center">
      <span>本网站由</span><a class="footer-support-logo" href="https://www.upyun.com" target="blank" title="又拍云">
        <img m="x-1" width="50" src="https://cdn.jsdelivr.net/gh/YunYouJun/cdn/img/logo/upyun-logo.png" alt="又拍云">
      </a><span>提供 CDN 加速</span>
    </div>
  </div>
</template>
