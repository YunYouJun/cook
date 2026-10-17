<script setup lang="ts">
import type { RecipeView } from '~/utils/settings'

const model = defineModel<RecipeView>({ required: true })
</script>

<template>
  <div class="recipe-view-switch" role="group" aria-label="做菜视图">
    <button class="view-option" :aria-pressed="model === 'classic'" @click="model = 'classic'">
      经典视图
    </button>
    <button class="view-option" :aria-pressed="model === 'recommendation'" @click="model = 'recommendation'">
      匹配视图
    </button>
  </div>
</template>

<style scoped>
.recipe-view-switch {
  display: flex;
  justify-content: center;
  gap: 4px;
  padding: 4px;
  width: fit-content;
  max-width: 100%;
  margin: 12px auto;
  background: var(--c-bg-alt);
  border: 1px solid var(--ylf-c-border);
  border-radius: 10px;
}
.view-option {
  min-height: 44px;
  padding: 8px 20px;
  border-radius: 6px;
  font-size: 14px;
  color: var(--c-text);
}
.view-option[aria-pressed='true'] {
  background: var(--c-bg);
  box-shadow: 0 1px 4px #0002;
  font-weight: 600;
}
.view-option:focus-visible {
  outline: 2px solid var(--c-primary);
  outline-offset: 2px;
}
</style>
