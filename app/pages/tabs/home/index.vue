<script lang="ts" setup>
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  alias: ['/', '/home', '/tabs'],
})

useHead({
  title: '食用手册',
})

const rStore = useRecipeStore()
const resultsBarRef = ref<{ $el: HTMLElement }>()
const flightTarget = computed(() => resultsBarRef.value?.$el)
const resultsOpen = ref(false)

const hasSelection = computed(() => rStore.selectedStuff.length > 0 || Boolean(rStore.curTool))

function reset() {
  rStore.reset()
  void yunleHaptic('medium')
}
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-title>做菜</ion-title>
        <ion-buttons slot="end">
          <ion-button :disabled="!hasSelection" @click="reset">
            清空
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true">
      <ion-header collapse="condense">
        <ion-toolbar>
          <ion-title size="large">
            做菜
          </ion-title>
        </ion-toolbar>
      </ion-header>

      <ChooseFood :flight-target="flightTarget" />
    </ion-content>

    <ResultsBar ref="resultsBarRef" @open="resultsOpen = true" />
    <ResultsSheet v-model:open="resultsOpen" />
  </ion-page>
</template>
