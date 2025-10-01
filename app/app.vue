<script setup lang="ts">
import { useIndexedDB } from '~/composables/db'
import { appName } from '~/constants'
import { installPrompt } from './utils/pwa'

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
  installPrompt()
  indexedDB.init()
})
</script>

<template>
  <VitePwaManifest />
  <!-- https://ionic.nuxtjs.org/cookbook/customising-app-vue -->
  <!-- <NuxtLayout>
    <NuxtLoadingIndicator />
    <NuxtPage />
  </NuxtLayout> -->
  <ion-app>
    <ion-router-outlet />
  </ion-app>
</template>
