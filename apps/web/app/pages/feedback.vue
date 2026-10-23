<script setup lang="ts">
import AppFeedbackForm from '~/components/feedback/AppForm.vue'
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/tabs/my" text="返回" />
        </ion-buttons>
        <ion-title>问题与建议</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content :fullscreen="true">
      <AppFeedbackForm />
    </ion-content>
  </ion-page>
</template>
