<script lang="ts" setup>
import { App } from '@capacitor/app'
import { Capacitor } from '@capacitor/core'
import { useBackButton } from '@ionic/vue'

useHead({
  title: 'Cook Tabs',
})

const router = useRouter()
const ionRouter = useIonRouter()

function isTabRootPath(path: string) {
  // Tabs are set up with aliases: '/', '/home', '/random', '/my'
  // Treat '/tabs' as root too for safety
  return ['/', '/home', '/random', '/my', '/tabs'].includes(path)
}

onMounted(() => {
  if (Capacitor.getPlatform() === 'android') {
    useBackButton(10, () => {
      if (isTabRootPath(router.currentRoute.value.path)) {
        App.minimizeApp()
      }
      else {
        ionRouter.back()
      }
    })
  }
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
