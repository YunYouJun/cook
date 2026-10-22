<script lang="ts" setup>
import type { RecipeMatch } from '@yunyoujun/cook'
import type { RecipeItem } from '~/types'
import { onIonViewDidEnter, onIonViewWillLeave } from '@ionic/vue'
import { useCookingStyle } from '~/composables/useCookingStyle'
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  alias: ['/', '/home', '/tabs'],
})

useHead({
  title: '食用手册',
})

const rStore = useRecipeStore()
const { style: cookingStyle, pantry: workbenchPantry, canPersist } = useCookingStyle()
const active = shallowRef(false)
onIonViewDidEnter(() => {
  active.value = true
})
onIonViewWillLeave(() => {
  active.value = false
})
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

const feedback = useRecipeFeedback()
const selection = () => ({ stuff: rStore.selectedStuff, tool: rStore.curTool || undefined, mode: rStore.curMode })

const detail = shallowRef<{ recipe: RecipeItem, match?: RecipeMatch }>()
const detailOpen = ref(false)
function openDetail(recipe: RecipeItem, match?: RecipeMatch) {
  detail.value = { recipe, match }
  detailOpen.value = true

  // 匿名记录「这组食材下点开了第几个结果」，用来评估和调整排序
  const rank = match ? rStore.recipeMatches.indexOf(match) : -1
  if (rank >= 0 && typeof recipe.id === 'number')
    feedback.signal({ type: 'open', ...selection(), recipeId: recipe.id, rank })
}

// 停在一组没有结果的食材上时记一次，用来发现缺哪些菜
watchDebounced(
  () => [rStore.isSearching, rStore.recipesLength, rStore.selectedStuff.join('、'), rStore.curTool, rStore.curMode, rStore.keyword, rStore.recipeMatches.length],
  () => {
    if (cookingStyle.value === 'classic' && !rStore.isSearching && rStore.recipesLength && rStore.selectedStuff.length && !rStore.keyword && !rStore.recipeMatches.length)
      feedback.signal({ type: 'empty', ...selection() })
  },
  { debounce: 3000 },
)
</script>

<template>
  <ion-page>
    <ion-header :translucent="true" :class="{ 'workbench-header': cookingStyle === 'workbench' }">
      <ion-toolbar>
        <ion-title>做菜</ion-title>
        <ion-buttons slot="end">
          <ion-button :disabled="!hasSelection" @click="reset">
            清空
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content ref="contentRef" :fullscreen="true" :class="{ 'workbench-content': cookingStyle === 'workbench' }" :style="{ '--padding-bottom': `${cookingStyle === 'classic' ? panelInset + 16 : 16}px` }">
      <ion-header v-show="cookingStyle === 'classic'" collapse="condense">
        <ion-toolbar>
          <ion-title size="large">
            做菜
          </ion-title>
        </ion-toolbar>
      </ion-header>

      <CookingStyleSwitch v-model="cookingStyle" />
      <p v-if="!canPersist" class="style-storage-note" role="status">
        浏览器暂时无法保存样式偏好，本次仍可切换使用。
      </p>
      <CookingWorkbench v-if="cookingStyle === 'workbench'" v-model:pantry="workbenchPantry" @detail="openDetail" />
      <ChooseFood v-else :flight-target="flightTarget" />
    </ion-content>

    <ResultsPanel v-if="cookingStyle === 'classic'" ref="panelRef" v-model:inset="panelInset" :active="active" @scroll-top="scrollTop" @detail="openDetail" />
    <RecipeDetailSheet v-model:open="detailOpen" :recipe="detail?.recipe" :match="detail?.match" />
  </ion-page>
</template>

<style scoped>
.workbench-header ion-toolbar {
  --opacity-scale: 1;
}
.workbench-header ion-toolbar.in-toolbar ion-title {
  opacity: 1;
  pointer-events: auto;
}
.workbench-content {
  --background: #faf8f3;
}
:global(.ion-palette-dark .workbench-content) {
  --background: #1e2722;
}
.style-storage-note {
  margin: 12px 20px;
  font-size: 14px;
  text-align: center;
}
</style>
