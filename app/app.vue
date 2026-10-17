<script setup lang="ts">
import { useIndexedDB } from '~/composables/db'
import { appName } from '~/constants'

// https://nuxt.com/docs/api/composables/use-head
useHead({
  title: appName,
  meta: [
    {
      name: 'description',
      content: '好的，今天我们来做菜！',
    },
  ],
})

const indexedDB = useIndexedDB()

onMounted(() => {
  indexedDB.init()
})
</script>

<template>
  <ArchiveBanner />
  <div class="relative">
    <NuxtLayout>
      <NuxtLoadingIndicator />
      <NuxtPage />
    </NuxtLayout>
  </div>
</template>
