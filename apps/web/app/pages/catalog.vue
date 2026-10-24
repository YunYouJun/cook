<script setup lang="ts">
definePageMeta({ layout: 'child' })
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>食材和厨具</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content :fullscreen="true">
      <PersonalCatalogManager />
    </ion-content>
  </ion-page>
</template>
