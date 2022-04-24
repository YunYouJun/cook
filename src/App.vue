<script setup lang="ts">
import { useThemeColor } from './composables/theme-color'
import { isDark } from '~/composables'

// https://github.com/vueuse/head
// you can use this to manipulate the document head in any components,
// they will be rendered correctly in the html results with vite-ssg
useHead({
  title: '隔离食用手册',
  meta: [
    { name: 'description', content: '好的，今天我们来做菜！' },
  ],
})

useThemeColor(isDark)
</script>

<template>
  <RouterView />
</template>
