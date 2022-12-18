<script lang="ts" setup>
import { isClient } from '@vueuse/core'

const displayICP = ref(true)

onBeforeMount(() => {
  if (isClient)
    displayICP.value = ['cook.yunyoujun.cn', 'localhost', '127.0.0.1'].includes(window.location.hostname)
})

const commitSha = (import.meta.env.VITE_COMMIT_REF || '').slice(0, 7)
const now = import.meta.env.VITE_APP_BUILD_TIME
const buildDate = (new Date(parseInt(now) * 1000)).toLocaleDateString()
</script>

<template>
  <div p="4" class="flex flex-col justify-center items-center" text="sm">
    <div v-if="commitSha && buildDate" mb-2>
      <span>
        当前版本（{{ buildDate }}）:
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
    <div m="t-2" opacity="80">
      <a href="https://yunle.fun" target="_blank" title="云乐坊">
        云乐坊工作室
      </a>
    </div>
    <!-- 欢迎赞助 -->
    <!-- <div m="t-2" opacity="80" class="footer-support flex justify-center items-center">
      <span>本网站由</span><a class="footer-support-logo" href="https://www.upyun.com" target="blank" title="又拍云">
        <img m="x-1" width="50" src="https://cdn.yunyoujun.cn/img/logo/upyun-logo.png" alt="又拍云">
      </a><span>提供 CDN 加速</span>
    </div> -->
  </div>
</template>
