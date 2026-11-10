<script setup lang="ts">
defineProps<{
  title: string
  icon?: string
}>()
</script>

<template>
  <span class="ingredient-section-label">
    <ion-icon v-if="icon" class="ingredient-section-label-icon" :icon="icon" aria-hidden="true" />
    <span>{{ title }}</span>
  </span>
</template>

<style scoped>
.ingredient-section-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  vertical-align: middle;
}
.ingredient-section-label-icon {
  flex: 0 0 14px;
  width: 14px;
  height: 14px;
  color: inherit;
  pointer-events: none;
}
</style>
