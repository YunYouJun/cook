<script lang="ts" setup>
import { getPrimaryNavigation } from '../config'

useHead({
  title: 'Cook Tabs',
})

const router = useRouter()

const navigation = computed(() => getPrimaryNavigation(router.currentRoute.value.path))

const tabsRef = ref<{ $el: HTMLElement }>()
const reducedMotion = usePreferredReducedMotion()

/**
 * 和 iOS 一样：再点一次当前 tab，页面滚回顶部
 */
function onTabClick(tab: string) {
  const active = navigation.value.some(item => item.tab === tab && item.active)
  if (!active)
    return
  const content = tabsRef.value?.$el.querySelector<HTMLIonContentElement>('.ion-page:not(.ion-page-hidden) ion-content')
  void content?.scrollToTop(reducedMotion.value ? 0 : 300)
}
</script>

<template>
  <ion-page>
    <ion-tabs ref="tabsRef">
      <ion-router-outlet />

      <ion-tab-bar slot="bottom" class="cook-mobile-tabs" :translucent="true">
        <!-- Ionic matches href to the current URL, including route aliases. -->
        <ion-tab-button v-for="item in navigation" :key="item.tab" :tab="item.tab" :href="item.active ? router.currentRoute.value.path : item.href" @click="onTabClick(item.tab)">
          <ion-icon :icon="item.active ? item.activeIcon : item.icon" aria-hidden="true" />
          <ion-label>{{ item.label }}</ion-label>
        </ion-tab-button>
      </ion-tab-bar>
    </ion-tabs>
  </ion-page>
</template>

<style scoped>
.cook-mobile-tabs ion-tab-button {
  --color: var(--ion-text-color-step-400, #6d6d72);
  --color-selected: var(--ion-color-primary);
}
@media (min-width: 1024px) {
  .cook-mobile-tabs {
    display: none;
  }
}
</style>
