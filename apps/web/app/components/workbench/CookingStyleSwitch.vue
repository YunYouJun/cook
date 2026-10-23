<script setup lang="ts">
import type { CookingStyle } from '~/composables/useCookingStyle'

const model = defineModel<CookingStyle>({ required: true })
</script>

<template>
  <div class="cooking-style-switch" role="group" aria-label="做菜界面样式">
    <button type="button" :aria-pressed="model === 'classic'" @click="model = 'classic'">
      经典模式
    </button>
    <button type="button" :aria-pressed="model === 'workbench'" @click="model = 'workbench'">
      料理工作台
    </button>
  </div>
</template>

<style scoped>
.cooking-style-switch {
  display: flex;
  width: min(340px, calc(100% - 32px));
  padding: 4px;
  margin: 18px auto;
  border-radius: 28px;
  background: #ebe9e5;
}
.cooking-style-switch button {
  flex: 1;
  min-height: 44px;
  padding: 8px 12px;
  border-radius: 24px;
  font-size: 15px;
  font-weight: 600;
  color: #233c34;
}
.cooking-style-switch button[aria-pressed='true'] {
  background: #a64128;
  color: #fff;
}
.cooking-style-switch button:focus-visible {
  outline: 3px solid var(--ion-color-primary, #244e40);
  outline-offset: 3px;
}
:global(.ion-palette-dark .cooking-style-switch) {
  background: #303d34;
}
:global(.ion-palette-dark .cooking-style-switch button) {
  color: #f2f6f3;
}
</style>
