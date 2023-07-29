<script setup lang="ts">
const color = useColorMode()

useHead({
  meta: [{
    id: 'theme-color',
    name: 'theme-color',
    content: () => color.value === 'dark' ? '#222222' : '#ffffff',
  }],
})

function toggleDark() {
  color.preference = color.value === 'dark' ? 'light' : 'dark'
}
</script>

<template>
  <button class="mx-2 icon-btn hover:text-yellow-400 !outline-none" title="切换" @click="toggleDark()">
    <div i="ri-sun-line dark:ri-moon-line" />
  </button>
</template>
