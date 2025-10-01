<script lang="ts" setup>
const app = useAppStore()

definePageMeta({
  layout: 'child',
})
</script>

<template>
  <ion-page>
    <ion-header class="ion-no-border">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>设置</ion-title>
        <!-- <ion-buttons slot="end">
          <ion-button color="dark">
            <ion-icon slot="icon-only" :icon="ioniconsPersonCircleOutline" />
          </ion-button>
        </ion-buttons> -->
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list-header>外观</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle :checked="isDark" justify="space-between" @ion-change="toggleDark">
            暗色模式
          </ion-toggle>
        </ion-item>
      </ion-list>

      <ion-list-header>数据</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <ion-toggle v-model:checked="app.settings.keepLocalData" justify="space-between">
            离开网页后保留选中数据
          </ion-toggle>
        </ion-item>
        <ion-item :button="true">
          更多设置，敬请期待
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>

<style>
/* (Optional) This is added to prevent the flashing that happens when toggling between palettes */
ion-item {
  --transition: none;
}
</style>
