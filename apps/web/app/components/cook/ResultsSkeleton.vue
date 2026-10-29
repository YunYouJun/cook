<script setup lang="ts">
defineProps<{ desktop?: boolean }>()
</script>

<template>
  <div class="results-skeleton" :class="{ 'is-desktop': desktop }" role="status" aria-live="polite">
    <span class="results-skeleton-label">
      正在加载菜谱…
    </span>
    <div aria-hidden="true" class="results-skeleton-list">
      <div v-for="row in 3" :key="row" class="results-skeleton-row">
        <span class="results-skeleton-thumb" />
        <div class="results-skeleton-lines">
          <span /><span />
        </div>
        <span class="results-skeleton-meta" />
        <span class="results-skeleton-chevron" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.results-skeleton-label {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.results-skeleton-list {
  margin: 8px 16px 16px;
  overflow: hidden;
  border-radius: 10px;
  background: var(--ion-item-background, #fff);
}
.results-skeleton-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  box-sizing: border-box;
  /* Ionic labels and the item separator make the rendered row 63px tall. */
  height: 63px;
  padding: 0 12px 0 16px;
}
.results-skeleton-row:not(:last-child)::after {
  position: absolute;
  inset-inline: 64px 0;
  bottom: 0;
  height: 0.5px;
  background: var(--ion-item-border-color, #d1d1d6);
  content: '';
}
.results-skeleton-thumb,
.results-skeleton-lines span,
.results-skeleton-meta,
.results-skeleton-chevron {
  display: block;
  border-radius: 6px;
  background: var(--cook-fill, #e5e5ea);
  animation: skeleton-pulse 2s ease-in-out infinite alternate;
}
.results-skeleton-thumb {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 8px;
}
.results-skeleton-lines {
  flex: 1;
  min-width: 0;
}
.results-skeleton-lines span {
  width: 65%;
  height: 14px;
}
.results-skeleton-lines span + span {
  width: 85%;
  height: 10px;
  margin-top: 9px;
}
.results-skeleton-meta {
  width: 28px;
  height: 10px;
  flex: none;
}
.results-skeleton-chevron {
  width: 6px;
  height: 10px;
  flex: none;
}
.is-desktop .results-skeleton-list {
  margin-inline: 10px;
  border-radius: 6px;
}
.is-desktop .results-skeleton-row {
  gap: 10px;
  height: 61px;
  padding-inline: 10px;
}
.is-desktop .results-skeleton-row::after {
  inset-inline-start: 50px;
}
.is-desktop .results-skeleton-thumb {
  width: 30px;
  height: 30px;
  border-radius: 6px;
}
@keyframes skeleton-pulse {
  to {
    opacity: 0.65;
  }
}
@media (prefers-reduced-motion: reduce) {
  .results-skeleton-thumb,
  .results-skeleton-lines span,
  .results-skeleton-meta,
  .results-skeleton-chevron {
    animation: none;
  }
}
</style>
