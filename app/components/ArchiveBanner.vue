<script lang="ts" setup>
import { useStorage } from '@vueuse/core'

const dismissed = useStorage('cook:archive-banner-dismissed', false)
</script>

<template>
  <div
    v-if="!dismissed"
    class="flex items-start justify-center gap-2"
    p="x-4 y-2"
    text="sm left amber-900 dark:amber-100"
    bg="amber-100 dark:amber-900"
    role="note"
  >
    <p leading-relaxed>
      这是食用手册的<b>经典版存档</b>，已不再更新。新版请访问
      <a href="https://cook.yunyoujun.cn" target="_blank" rel="noopener" font-bold underline>cook.yunyoujun.cn</a>
      <span op-80>（两边是独立站点，此处的收藏、历史等本地数据不会同步到新版）</span>
    </p>
    <button
      shrink-0 p-1 op-70 hover:op-100
      aria-label="关闭提示"
      title="关闭提示"
      @click="dismissed = true"
    >
      <div i-ri-close-line />
    </button>
  </div>
</template>
