<script setup lang="ts">
// import { installPrompt } from './utils/pwa'
import { isClient } from '@vueuse/core'
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
const { isDark, setDarkMode, setLightMode } = useDarkMode()

onMounted(() => {
  // init dark mode
  if (isClient) {
    if (isDark.value) {
      setDarkMode()
    }
    else {
      setLightMode()
    }

    indexedDB.init()
  }

  // installPrompt()
})
</script>

<template>
  <!-- <VitePwaManifest /> -->
  <!-- https://ionic.nuxtjs.org/cookbook/customising-app-vue -->
  <!-- <NuxtLayout>
    <NuxtLoadingIndicator />
    <NuxtPage />
  </NuxtLayout> -->
  <ion-app>
    <ion-router-outlet />
  </ion-app>
</template>
