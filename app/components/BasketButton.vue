<script lang="ts" setup>
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import { recipePanelRef } from '~/composables/global'

const rStore = useRecipeStore()
const { isDark } = useDarkMode()
const panelVisible = useElementVisibility(recipePanelRef)

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
        <span class="recipe-count">{{ panelVisible ? `${rStore.selectedStuff.length} 种` : `${rStore.displayedRecipe.length} 道` }}</span>
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
  font-size: var(--cook-match-font-secondary);
  font-weight: 400;
}
.view-recipes-label {
  display: inline-flex;
  align-items: center;
  gap: var(--ylf-space-2);
}
.view-recipes:focus-visible {
  outline: 3px solid var(--cook-match-accent);
  outline-offset: 3px;
}
</style>
