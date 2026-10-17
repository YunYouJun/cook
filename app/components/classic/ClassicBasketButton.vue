<script setup lang="ts">
import { recipePanelRef } from '~/composables/global'

const rStore = useRecipeStore()
function scrollToRecipes() {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  recipePanelRef.value?.scrollIntoView({ behavior: reduceMotion ? 'instant' : 'smooth', block: 'start' })
}
</script>

<template>
  <ion-fab slot="fixed" horizontal="end" vertical="bottom">
    <ion-fab-button size="small" aria-label="打开食材篮操作">
      <div v-if="rStore.displayedRecipe.length" i-mdi-bowl-mix-outline />
      <div v-else i-mdi-bowl-outline />
    </ion-fab-button>
    <ion-fab-list side="top">
      <ion-fab-button aria-label="重置食材和厨具" @click="rStore.reset">
        <ion-icon :icon="ioniconsTrashOutline" />
      </ion-fab-button>
      <ion-fab-button aria-label="查看经典菜谱" @click="scrollToRecipes">
        <ion-icon :icon="ioniconsListOutline" />
      </ion-fab-button>
    </ion-fab-list>
  </ion-fab>
</template>
