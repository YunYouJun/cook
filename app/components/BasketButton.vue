<script lang="ts" setup>
import { storeToRefs } from 'pinia'

const rStore = useRecipeStore()
const { displayedRecipe } = storeToRefs(rStore)

/**
 * scroll into view
 */
function recipePanelScrollIntoView() {
  const panel = recipePanelRef.value as HTMLElement
  if (panel) {
    panel.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }
}
</script>

<template>
  <ion-fab slot="fixed" horizontal="end" vertical="bottom">
    <!-- <button
      v-show="showBasketBtn"
      class="rounded rounded-full inline-flex cursor-pointer shadow items-center justify-center fixed z-9 hover:shadow-md"
      bg="green-50 dark:green-900" w="10" h="10"
      bottom="22"
      right="4"
      text="green-600 dark:green-300"
    >

    </button> -->

    <ion-fab-button size="small">
      <!-- <ion-icon :icon="add" /> -->
      <span v-if="displayedRecipe.length > 0">
        <div i-mdi-bowl-mix-outline />
      </span>
      <span v-else>
        <div i-mdi-bowl-outline />
      </span>
    </ion-fab-button>

    <ion-fab-list side="top">
      <ion-fab-button @click="rStore.reset">
        <ion-icon :icon="ioniconsTrashOutline" />
      </ion-fab-button>
      <ion-fab-button @click="recipePanelScrollIntoView">
        <ion-icon :icon="ioniconsListOutline" />
      </ion-fab-button>
    </ion-fab-list>
  </ion-fab>
</template>
