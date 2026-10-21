<script setup lang="ts">
definePageMeta({
  alias: ['/apps'],
})

useHead({
  title: '发现',
})

const { openAppFeedback } = useAppFeedback()
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-title>发现</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content :fullscreen="true">
      <ion-header collapse="condense">
        <ion-toolbar>
          <ion-title size="large">
            发现
          </ion-title>
        </ion-toolbar>
      </ion-header>

      <ion-list :inset="true">
        <ion-item router-link="/apps/random">
          <CookListIcon slot="start" :icon="ioniconsShuffleOutline" color="#ff9500" />
          <ion-label>今天吃什么</ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item button :detail="true" @click="openAppFeedback('idea')">
          <CookListIcon slot="start" :icon="ioniconsBulbOutline" color="#34c759" />
          <ion-label>新功能建议</ion-label>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
