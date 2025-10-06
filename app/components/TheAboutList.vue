<script lang="ts" setup>
import { isClient } from '@vueuse/core'
import pkg from '~/../package.json'
import { icp } from '../constants'

const displayICP = ref(true)

const commitSha = (import.meta.env.VITE_COMMIT_REF || '').slice(0, 7)
const date = import.meta.env.VITE_APP_BUILD_DATE
const buildDate = (new Date(date)).toLocaleDateString()

onBeforeMount(() => {
  if (isClient)
    displayICP.value = ['cook.yunyoujun.cn', 'localhost', '127.0.0.1'].includes(window.location.hostname)
})
</script>

<template>
  <ion-list :inset="true">
    <ion-item>
      <ion-label>当前版本</ion-label>
      <ion-text>v{{ pkg.version }}</ion-text>
    </ion-item>

    <ion-item>
      <ion-label>构建时间</ion-label>
      <ion-text>{{ buildDate }}</ion-text>
    </ion-item>

    <ion-item v-if="commitSha" :href="`https://github.com/YunYouJun/cook/commit/${commitSha}`" target="_blank">
      <ion-label>Commit Hash</ion-label>
      <ion-text>{{ commitSha }}</ion-text>
    </ion-item>

    <ion-item v-if="displayICP" href="https://beian.miit.gov.cn/" target="_blank">
      <ion-label>备案号</ion-label>
      <ion-text>{{ icp }}</ion-text>
    </ion-item>
  </ion-list>

  <ion-list :inset="true">
    <ion-item href="https://www.bilibili.com/blackboard/dynamic/306882" target="_blank">
      <ion-label>数据来源</ion-label>
      <ion-text class="inline-flex items-center justify-center">
        <div class="inline-flex" i-ri-bilibili-line />
        <span m="l-1" class="inline-flex">哔哩哔哩</span>
      </ion-text>
    </ion-item>

    <ion-item href="https://github.com/YunYouJun/cook" target="_blank">
      <ion-label>开源代码</ion-label>
      <ion-text class="inline-flex items-center justify-center">
        <div class="inline-flex" i-ri-github-line />
        <span m="l-1" class="inline-flex">GitHub</span>
      </ion-text>
    </ion-item>

    <ion-item router-link="/about/me">
      <ion-label>项目作者</ion-label>
      <ion-text class="inline-flex items-center justify-center">
        云游君
      </ion-text>
    </ion-item>
  </ion-list>
</template>
