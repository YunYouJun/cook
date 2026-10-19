<script lang="ts" setup>
/**
 * 食材 / 厨具选择按钮：未选中是中性灰底，选中后填充主色并显示勾
 */
defineProps<{
  label: string
  selected: boolean
  emoji?: string
  icon?: string
}>()
</script>

<template>
  <button type="button" class="ingredient-chip" :aria-pressed="selected">
    <ion-icon v-if="selected" class="ingredient-chip-mark" :icon="ioniconsCheckmark" aria-hidden="true" />
    <span v-else-if="emoji" class="ingredient-chip-mark" aria-hidden="true">{{ emoji }}</span>
    <span v-else-if="icon" class="ingredient-chip-mark" :class="icon" aria-hidden="true" />
    <span>{{ label }}</span>
  </button>
</template>

<style scoped>
.ingredient-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 34px;
  padding: 0 12px;
  border-radius: 17px;
  background: var(--cook-fill);
  color: var(--ion-text-color, #000);
  font-size: 15px;
  line-height: 1;
  transition:
    transform 0.2s cubic-bezier(0.2, 0.8, 0.2, 1),
    background-color 0.2s ease,
    color 0.2s ease;
  -webkit-user-select: none;
  user-select: none;
}
.ingredient-chip:active {
  transform: scale(0.94);
}
.ingredient-chip[aria-pressed='true'] {
  background: var(--ion-color-primary);
  color: var(--ion-color-primary-contrast);
  font-weight: 500;
  animation: ingredient-chip-pop 0.32s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.ingredient-chip-mark {
  display: inline-flex;
  font-size: 15px;
}
.ingredient-chip:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 2px;
}
@keyframes ingredient-chip-pop {
  0% {
    transform: scale(0.92);
  }
  60% {
    transform: scale(1.05);
  }
  100% {
    transform: scale(1);
  }
}
@media (prefers-reduced-motion: reduce) {
  .ingredient-chip {
    transition: none;
  }
  .ingredient-chip:active {
    transform: none;
  }
  .ingredient-chip[aria-pressed='true'] {
    animation: none;
  }
}
</style>
