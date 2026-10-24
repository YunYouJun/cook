<script setup lang="ts">
import type { RecipeItem } from '~/types'
import { getRecipeFeedbackLink } from '~/utils/recipe'
import RecipeForm from './RecipeForm.vue'

const props = defineProps<{ recipe: RecipeItem, compact?: boolean }>()
const reporting = ref(false)
function open() {
  if (typeof props.recipe.id === 'number')
    reporting.value = true
  else
    window.open(getRecipeFeedbackLink(props.recipe), '_blank', 'noopener,noreferrer')
}
</script>

<template>
  <span class="recipe-feedback-entry" @click.stop>
    <button type="button" class="recipe-feedback-button" :aria-label="`反馈${recipe.name}的问题`" @click.stop="open">
      <span class="i-carbon-chat" aria-hidden="true" />
      {{ compact ? '反馈' : '反馈这道菜的问题' }}
    </button>
    <RecipeForm v-if="reporting" :recipe="recipe" @close="reporting = false" />
  </span>
</template>

<style scoped>
.recipe-feedback-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 44px;
  padding: 8px;
  color: var(--ion-color-primary);
  background: transparent;
  border: 0;
  font: inherit;
  font-size: 14px;
  white-space: nowrap;
  cursor: pointer;
}
.recipe-feedback-button:focus-visible {
  outline: 2px solid currentColor;
  border-radius: 6px;
}
</style>
