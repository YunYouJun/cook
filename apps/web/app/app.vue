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
    // Keep Ionic surfaces in sync when the system appearance changes at runtime.
    watch(isDark, (dark) => {
      if (dark)
        void setDarkMode()
      else
        void setLightMode()
    }, { immediate: true })

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
