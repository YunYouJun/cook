<script lang="ts" setup>
definePageMeta({
  alias: ['/', '/home', '/tabs'],
})

useHead({
  title: '食用手册',
})

const rStore = useRecipeStore()
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>
          <button
            class="m-auto flex cursor-pointer items-center gap-2 transition active:text-green-800 hover:(text-green-600)"
            title="重置"
            @click="rStore.reset"
          >
            <div v-if="rStore.selectedStuff.length" i-mdi-pot-steam-outline />
            <div v-else i-mdi-pot-mix-outline />

            <span>
              好的，今天我们来做菜！
            </span>
          </button>
        </ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content class="text-center">
      <ChooseFood />
      <SimpleCopyright />
    </ion-content>
  </ion-page>
</template>
