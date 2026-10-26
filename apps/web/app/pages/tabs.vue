<script lang="ts" setup>
import { tabRootPaths } from '../config'

useHead({
  title: 'Cook Tabs',
})

const router = useRouter()

function isTabRootPath(path: string) {
  return tabRootPaths.includes(path)
}

const tabsRef = ref<{ $el: HTMLElement }>()
const reducedMotion = usePreferredReducedMotion()

/**
 * 和 iOS 一样：再点一次当前 tab，页面滚回顶部
 */
function onTabClick(tab: string) {
  const path = router.currentRoute.value.path
  const active = path === `/${tab}` || (tab === 'home' && isTabRootPath(path) && !['/apps', '/my'].includes(path))
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

      <ion-tab-bar slot="bottom" :translucent="true">
        <ion-tab-button tab="home" href="/home" @click="onTabClick('home')">
          <ion-icon :icon="ioniconsBookOutline" />
          <ion-label>做菜</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="library" href="/library" @click="onTabClick('library')">
          <ion-icon :icon="ioniconsLibraryOutline" />
          <ion-label>菜谱</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="apps" href="/apps" @click="onTabClick('apps')">
          <ion-icon :icon="ioniconsRestaurantOutline" />
          <ion-label>发现</ion-label>
        </ion-tab-button>

        <ion-tab-button tab="my" href="/my" @click="onTabClick('my')">
          <ion-icon :icon="ioniconsPersonCircleOutline" />
          <ion-label>我的</ion-label>
        </ion-tab-button>
      </ion-tab-bar>
    </ion-tabs>
  </ion-page>
</template>
