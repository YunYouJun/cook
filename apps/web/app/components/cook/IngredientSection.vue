<script lang="ts" setup>
/**
 * iOS 分组：灰色小标题 + 白色圆角卡片，卡片里是一组可换行的按钮
 */
defineProps<{
  title: string
  /** 标题右侧的说明，例如「已选 2 种」 */
  note?: string
}>()
</script>

<template>
  <section class="ingredient-section" :aria-label="title">
    <h2 class="cook-group-header ingredient-section-header">
      <span>{{ title }}</span>
      <span v-if="note" class="ingredient-section-note">{{ note }}</span>
    </h2>
    <div class="ingredient-section-card">
      <slot />
    </div>
  </section>
</template>

<style scoped>
.ingredient-section-header {
  display: flex;
  justify-content: space-between;
  margin: var(--ingredient-section-heading-margin, 24px 0 0);
  padding: var(--ingredient-section-heading-padding, 0 36px 6px);
  color: var(--ingredient-section-heading-color, var(--ion-text-color-step-400, #6d6d72));
  font-weight: var(--ingredient-section-heading-weight, 400);
}
.ingredient-section-note {
  color: var(--ion-color-primary);
  font-weight: 400;
}
.ingredient-section-card {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ingredient-section-gap, 8px);
  margin: var(--ingredient-section-margin, 0 16px);
  padding: var(--ingredient-section-padding, 12px);
  border: var(--ingredient-section-border, 0);
  border-radius: var(--ingredient-section-radius, 10px);
  background: var(--ion-item-background, #fff);
}
</style>
