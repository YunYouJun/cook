<script setup lang="ts">
// https://github.com/vueuse/head
// you can use this to manipulate the document head in any components,
import { useWeixin } from './composables/weixin'

// they will be rendered correctly in the html results with vite-ssg
useHead({
  title: '好的，今天我们来做菜！',
  meta: [
    { name: 'description', content: '好的，今天我们来做菜！' },
  ],
})

useWeixin()
</script>

<template>
  <RouterView />
</template>
