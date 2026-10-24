<script setup lang="ts">
import type { CookingStyle } from '~/composables/useCookingStyle'

const model = defineModel<CookingStyle>({ required: true })
</script>

<template>
  <div class="cooking-style-switch" role="group" aria-label="做菜视图">
    <button type="button" :aria-pressed="model === 'classic'" @click="model = 'classic'">
      分组选择
    </button>
    <button type="button" :aria-pressed="model === 'workbench'" @click="model = 'workbench'">
      并排浏览
    </button>
  </div>
</template>

<style scoped>
.cooking-style-switch {
  display: flex;
  width: min(340px, calc(100% - 32px));
  padding: 4px;
  margin: 18px auto;
  border-radius: 11px;
  background: var(--cook-fill);
}
.cooking-style-switch button {
  flex: 1;
  min-height: 44px;
  padding: 8px 12px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--ion-text-color);
}
.cooking-style-switch button[aria-pressed='true'] {
  background: var(--ion-item-background, #fff);
  box-shadow: 0 1px 3px #00000014;
}
.cooking-style-switch button:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 3px;
}
</style>
