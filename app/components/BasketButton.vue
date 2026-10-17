<script lang="ts" setup>
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import { recipePanelRef } from '~/composables/global'

const rStore = useRecipeStore()
const { isDark } = useDarkMode()
const panelVisible = useElementVisibility(recipePanelRef)

const count = computed(() => panelVisible.value ? rStore.selectedStuff.length : rStore.displayedRecipe.length)
// 数字变大从下往上滚，变小从上往下滚
const rollDirection = shallowRef(1)
watch(count, (value, oldValue) => {
  rollDirection.value = value >= oldValue ? 1 : -1
})

function scrollToRecipes() {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const target = panelVisible.value ? document.getElementById('ingredient-basket') : recipePanelRef.value
  target?.scrollIntoView({ behavior: reduceMotion ? 'instant' : 'smooth', block: 'start' })
}
</script>

<template>
  <ion-fab slot="fixed" class="cook-match-theme ylf-theme-light" :class="{ 'ylf-theme-dark': isDark }" horizontal="end" vertical="bottom">
    <YlfButton type="button" class="view-recipes" variant="primary" @click="scrollToRecipes">
      <span class="view-recipes-label">
        <span aria-hidden="true">🍲</span>
        {{ panelVisible ? '调整食材' : '看推荐' }}
        <span class="recipe-count" :style="{ '--roll': rollDirection }">
          <Transition name="count-roll" mode="out-in">
            <span :key="count" class="recipe-count-value">{{ count }}</span>
          </Transition>
          {{ panelVisible ? '种' : '道' }}
        </span>
      </span>
    </YlfButton>
  </ion-fab>
</template>

<style scoped>
.view-recipes {
  display: flex;
  align-items: center;
  gap: var(--ylf-space-2);
  min-height: var(--ylf-space-12);
  margin: 0 var(--ylf-space-2) var(--ylf-space-2);
  padding: var(--ylf-space-3) var(--ylf-space-4);
  box-shadow: var(--ylf-shadow);
  font-weight: 600;
}
.recipe-count {
  display: inline-flex;
  align-items: baseline;
  gap: 0.25em;
  overflow: hidden;
  font-size: var(--cook-match-font-secondary);
  font-weight: 400;
}
.recipe-count-value {
  display: inline-block;
  min-width: 1ch;
  font-variant-numeric: tabular-nums;
}
.count-roll-enter-active,
.count-roll-leave-active {
  transition:
    transform 0.14s ease,
    opacity 0.14s ease;
}
.count-roll-enter-from {
  opacity: 0;
  transform: translateY(calc(var(--roll) * 0.8em));
}
.count-roll-leave-to {
  opacity: 0;
  transform: translateY(calc(var(--roll) * -0.8em));
}
.view-recipes-label {
  display: inline-flex;
  align-items: center;
  gap: var(--ylf-space-2);
}
@media (prefers-reduced-motion: reduce) {
  .count-roll-enter-active,
  .count-roll-leave-active {
    transition: none;
  }
}
.view-recipes:focus-visible {
  outline: 3px solid var(--cook-match-accent);
  outline-offset: 3px;
}
</style>
