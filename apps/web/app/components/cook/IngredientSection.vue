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
}
.ingredient-section-note {
  color: var(--ion-color-primary);
}
.ingredient-section-card {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 16px;
  padding: 12px;
  border-radius: 10px;
  background: var(--ion-item-background, #fff);
}
</style>
