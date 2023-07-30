<script lang="ts" setup>
import { links } from '~/constants'
</script>

<template>
  <div>
    <a
      m="2"
      class="feedback-button"
      :href="links.contribute" target="_blank"
      title="居家菜谱投稿"
    >
      立即投稿
    </a>

    <a
      m="2"
      class="feedback-button"
      :href="links.feedback" target="_blank"
      alt="通过兔小巢反馈"
    >
      立即反馈
    </a>
  </div>
</template>

<style>
.feedback-button {
  @apply border-none inline-flex justify-center rounded-md bg-blue-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-blue-500 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-blue-600;
}
</style>
