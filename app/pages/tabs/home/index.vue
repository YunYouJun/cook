<script lang="ts" setup>
import type { RecipeMatch } from '@yunyoujun/cook'
import type { RecipeItem } from '~/types'
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  alias: ['/', '/home', '/tabs'],
})

useHead({
  title: '食用手册',
})

const rStore = useRecipeStore()
const panelRef = ref<{ $el: HTMLElement }>()
const flightTarget = computed(() => panelRef.value?.$el)
const contentRef = ref<{ $el: HTMLIonContentElement }>()
const reducedMotion = usePreferredReducedMotion()

/** 结果弹层遮住的高度，留作页面底部空白 */
const panelInset = ref(0)

const hasSelection = computed(() => rStore.selectedStuff.length > 0 || Boolean(rStore.curTool))

function reset() {
  rStore.reset()
  void yunleHaptic('medium')
}

function scrollTop() {
  void contentRef.value?.$el.scrollToTop(reducedMotion.value === 'reduce' ? 0 : 300)
}

const detail = shallowRef<{ recipe: RecipeItem, match?: RecipeMatch }>()
const detailOpen = ref(false)
function openDetail(recipe: RecipeItem, match?: RecipeMatch) {
  detail.value = { recipe, match }
  detailOpen.value = true
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

    <ion-content ref="contentRef" :fullscreen="true" :style="{ '--padding-bottom': `${panelInset + 16}px` }">
      <ion-header collapse="condense">
        <ion-toolbar>
          <ion-title size="large">
            做菜
          </ion-title>
        </ion-toolbar>
      </ion-header>

      <ChooseFood :flight-target="flightTarget" />
    </ion-content>

    <ResultsPanel ref="panelRef" v-model:inset="panelInset" @scroll-top="scrollTop" @detail="openDetail" />
    <RecipeDetailSheet v-model:open="detailOpen" :recipe="detail?.recipe" :match="detail?.match" />
  </ion-page>
</template>
