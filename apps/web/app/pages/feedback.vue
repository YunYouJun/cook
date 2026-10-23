<script setup lang="ts">
import { onIonViewWillEnter } from '@ionic/vue'
import AppFeedbackForm from '~/components/feedback/AppForm.vue'
import { parseAppFeedbackCategory } from '~/utils/feedback'

const router = useRouter()
// Ionic owns the route outlet; read its current route on entry, including cached pages.
const initialCategory = shallowRef(parseAppFeedbackCategory(router.currentRoute.value.query.category))
onIonViewWillEnter(() => {
  initialCategory.value = parseAppFeedbackCategory(router.currentRoute.value.query.category)
})
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/tabs/my" text="返回" />
        </ion-buttons>
        <ion-title>问题与建议</ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content :fullscreen="true">
      <AppFeedbackForm :key="initialCategory" :initial-category="initialCategory" />
    </ion-content>
  </ion-page>
</template>
