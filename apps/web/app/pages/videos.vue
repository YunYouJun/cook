<script setup lang="ts">
definePageMeta({ layout: 'child' })
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/recipes/mine" />
        </ion-buttons>
        <ion-title>本机视频</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content><LocalVideoLibrary /></ion-content>
  </ion-page>
</template>
