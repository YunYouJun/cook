<script lang="ts" setup>
import { isClient } from '@vueuse/core'
import pkg from '~/package.json'

const displayICP = ref(true)

onBeforeMount(() => {
  if (isClient)
    displayICP.value = ['cook.yunyoujun.cn', 'localhost', '127.0.0.1'].includes(window.location.hostname)
})

const commitSha = (import.meta.env.VITE_COMMIT_REF || '').slice(0, 7)
const now = import.meta.env.VITE_APP_BUILD_TIME
const buildDate = (new Date(Number.parseInt(now) * 1000)).toLocaleDateString()
</script>

<template>
  <div p="4 t-2" class="flex flex-col items-center justify-center" text="sm">
    <div v-if="commitSha && buildDate" mb-2>
      <span>
        当前版本 v{{ pkg.version }}（{{ buildDate }}）:
      </span>
      <span>
        <a border="b-1 dashed" :href="`https://github.com/YunYouJun/cook/commit/${commitSha}`" target="_blank" alt="Cook | GitHub Commit">
          {{ commitSha }}
        </a>
      </span>
    </div>
    <a v-if="displayICP" opacity="80" class="flex" href="https://beian.miit.gov.cn/" target="_blank">
      苏ICP备17038157号
    </a>
    <div m="t-2" class="inline-flex items-center justify-center" text="xs">
      <a class="inline-flex items-center justify-center" style="color: #ea7b99" href="https://www.bilibili.com/blackboard/dynamic/306882" target="_blank">
        <span inline-flex>菜谱视频来源：</span>
        <div class="inline-flex" i-ri-bilibili-line />
        <span m="l-1" class="inline-flex" style="margin-top: 1px;">B 站</span>
      </a>
    </div>
    <div mt-2>
      本站点由
      <a color="#F6821F" href="https://www.cloudflare-cn.com/" target="_blank" title="Cloudflare" border="b-1 dashed">
        <span>Cloudflare</span>
      </a>
      提供 CDN 支持
    </div>
    <div m="t-2" opacity="80" class="flex items-center justify-center">
      ©️&nbsp;<a href="https://github.com/YunYouJun/cook" target="_blank">Cook</a>
      <div text="xs" m="x-1" i-ri-cloud-line />
      <a href="https://www.yunyoujun.cn" target="_blank">云游君</a>
    </div>
    <div m="t-2" opacity="80">
      <a href="https://yunle.fun" target="_blank" title="云乐坊">
        云乐坊工作室
      </a>
    </div>
  </div>
</template>
