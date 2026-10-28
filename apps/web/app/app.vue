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
const sidebarCollapsed = shallowRef(false)

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
  <ion-app class="cook-app-shell" :class="{ 'sidebar-collapsed': sidebarCollapsed }">
    <DesktopSidebar v-model:collapsed="sidebarCollapsed" />
    <ion-router-outlet class="cook-main-outlet" />
  </ion-app>
</template>

<style scoped>
@media (min-width: 1024px) {
  .cook-app-shell {
    --cook-sidebar-width: 208px;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  }
  .cook-app-shell.sidebar-collapsed {
    --cook-sidebar-width: 72px;
  }
  .cook-main-outlet {
    inset-inline-start: var(--cook-sidebar-width);
  }
  .cook-main-outlet :deep(.ion-page > ion-header > ion-toolbar) {
    --min-height: 56px;
    --padding-start: 20px;
    --padding-end: 16px;
    --opacity-scale: 1;
  }
  .cook-main-outlet :deep(.ion-page > ion-header ion-title) {
    position: static;
    width: auto;
    padding-inline: 0;
    font-size: 17px;
    text-align: start;
    transform: none;
    opacity: 1;
    pointer-events: auto;
  }
  .cook-main-outlet :deep(ion-content > ion-header[collapse='condense'] ion-toolbar:has(ion-title[size='large'])) {
    display: none;
  }
  .cook-main-outlet :deep(.ion-page > ion-content:not(.desktop-classic-content):not(.workbench-content)) {
    --padding-start: 16px;
    --padding-end: 16px;
    --padding-top: 16px;
    --padding-bottom: 24px;
  }
}
</style>
