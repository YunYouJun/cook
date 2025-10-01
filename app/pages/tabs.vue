<script lang="ts" setup>
useHead({
  title: 'Cook Tabs',
})
</script>

<template>
  <ion-page>
    <ion-content>
      <ion-tabs>
        <ion-router-outlet />

        <ion-tab-bar slot="bottom">
          <ion-tab-button tab="home" href="/home">
            <ion-icon :icon="ioniconsBookOutline" />
            <ion-label>做菜</ion-label>
          </ion-tab-button>

          <IonTabButton tab="random" href="/random">
            <ion-icon :icon="ioniconsRestaurantOutline" />
            <ion-label>吃什么</ion-label>
          </IonTabButton>

          <!-- <IonTabButton tab="tab3" href="/tabs/tab3">
            <ion-icon :icon="ioniconsBulbOutline" />
            <ion-label>Tab 3</ion-label>
          </IonTabButton> -->

          <IonTabButton tab="my" href="/my">
            <ion-icon :icon="ioniconsPersonCircleOutline" />
            <ion-label>我的</ion-label>
          </IonTabButton>
        </ion-tab-bar>
      </ion-tabs>
    </ion-content>
  </ion-page>
</template>
