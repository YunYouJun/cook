<script setup lang="ts">
import { links } from '~/constants/links'
</script>

<template>
  <a class="legacy-version-link" :href="links.legacyCook">
    <span>切换到旧版</span>
    <ion-icon :icon="ioniconsOpenOutline" aria-hidden="true" />
  </a>
</template>

<style scoped>
.legacy-version-link {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 32px;
  padding: 6px 10px;
  border: 0;
  border-radius: 6px;
  color: var(--ion-text-color-step-400, #636366);
  font-size: 13px;
  text-decoration: none;
}
.legacy-version-link ion-icon {
  flex: none;
  font-size: 14px;
}
.legacy-version-link:hover {
  background: var(--cook-fill);
  color: var(--ion-text-color, #1c1c1e);
}
.legacy-version-link:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 2px;
}
</style>
