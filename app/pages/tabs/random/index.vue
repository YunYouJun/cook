<script setup lang="ts">
definePageMeta({
  alias: ['/random'],
})

useHead({
  title: '今天吃什么',
})
</script>

<template>
  <ion-page flex flex-col>
    <ion-header>
      <ion-toolbar>
        <ion-title>
          今天吃什么
        </ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content flex flex-grow flex-col items-center justify-center>
      <RandomRecipe />
    </ion-content>
  </ion-page>
</template>
