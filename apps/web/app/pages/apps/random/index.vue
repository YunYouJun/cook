<script setup lang="ts">
import { cookDocumentHead, publicPages } from '~/constants'

definePageMeta({
  alias: ['/random'],
})

useHead(cookDocumentHead(publicPages.random))
</script>

<template>
  <ion-page flex flex-col>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/apps" />
        </ion-buttons>

        <ion-title>
          今天吃什么
        </ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <div class="flex flex-grow flex-col items-center justify-center">
        <RandomRecipe />
      </div>
    </ion-content>
  </ion-page>
</template>
