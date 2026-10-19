<template>
  <div class="results-skeleton" role="status" aria-live="polite" aria-label="正在加载菜谱">
    <p class="results-skeleton-label">
      正在加载菜谱…
    </p>
    <div aria-hidden="true" class="results-skeleton-list">
      <div v-for="row in 3" :key="row" class="results-skeleton-row">
        <span class="results-skeleton-thumb" />
        <div class="results-skeleton-lines">
          <span /><span />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.results-skeleton {
  padding: 12px 16px;
}
.results-skeleton-label {
  margin: 0 16px 12px;
  color: var(--ion-color-medium);
  font-size: 14px;
}
.results-skeleton-list {
  overflow: hidden;
  border-radius: 12px;
  background: var(--ion-item-background, #fff);
}
.results-skeleton-row {
  display: flex;
  align-items: center;
  gap: 12px;
  height: 68px;
  padding: 12px 16px;
}
.results-skeleton-thumb,
.results-skeleton-lines span {
  display: block;
  border-radius: 6px;
  background: var(--cook-fill, #e5e5ea);
  animation: skeleton-pulse 1.6s ease-in-out infinite alternate;
}
.results-skeleton-thumb {
  width: 36px;
  height: 36px;
  flex: none;
}
.results-skeleton-lines {
  flex: 1;
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
@keyframes skeleton-pulse {
  to {
    opacity: 0.45;
  }
}
@media (prefers-reduced-motion: reduce) {
  .results-skeleton-thumb,
  .results-skeleton-lines span {
    animation: none;
  }
}
</style>
