<script setup lang="ts">
import { useStorage } from '@vueuse/core'
import { iosAppName, links, namespace } from '~/constants'

const { dismissible = false } = defineProps<{
  dismissible?: boolean
}>()

const dismissed = useStorage(`${namespace}:ios-beta-banner-dismissed`, false, undefined, {
  writeDefaults: false,
  // Optional promotion: keep dismissal usable in memory if storage is unavailable.
  onError: () => undefined,
})
</script>

<template>
  <aside v-if="!dismissible || !dismissed" class="ios-beta-card" :aria-label="`${iosAppName} iOS 内测`">
    <img class="ios-beta-icon" src="/brand/cook-app-icon.svg" alt="" width="44" height="44">
    <div class="ios-beta-copy">
      <h2>{{ iosAppName }} iOS 内测</h2>
      <p>iPhone / iPad · iOS 17+</p>
    </div>
    <button v-if="dismissible" class="ios-beta-close" type="button" aria-label="关闭 iOS 内测提示" @click="dismissed = true">
      <span aria-hidden="true">×</span>
    </button>
    <div class="ios-beta-actions">
      <a class="ios-beta-join" :href="links.testflight" target="_blank" rel="noopener noreferrer">加入 TestFlight</a>
      <a class="ios-beta-guide" :href="links.iosBetaGuide" target="_blank" rel="noopener noreferrer">安装与反馈指南</a>
    </div>
    <p class="ios-beta-note">
      测试版，名额与可用版本以 TestFlight 为准。
    </p>
  </aside>
</template>

<style scoped>
.ios-beta-card {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px 12px;
  margin: 12px 16px 16px;
  padding: 16px;
  border: 1px solid var(--ion-color-primary);
  border-color: color-mix(in srgb, var(--ion-color-primary) 22%, transparent);
  border-radius: 16px;
  background: var(--ion-item-background, #fff);
  color: var(--ion-text-color, #1c1c1e);
}
.ios-beta-icon {
  border-radius: 10px;
}
.ios-beta-copy h2 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
  line-height: 22px;
}
.ios-beta-copy p,
.ios-beta-note {
  margin: 0;
  color: var(--ion-text-color-step-400, #636366);
  font-size: 12px;
  line-height: 18px;
}
.ios-beta-close {
  display: grid;
  place-items: center;
  align-self: start;
  width: 44px;
  height: 44px;
  margin: -8px -8px 0 0;
  border-radius: 50%;
  color: var(--ion-text-color-step-400, #636366);
  font-size: 26px;
  cursor: pointer;
}
.ios-beta-actions {
  grid-column: 2 / -1;
  display: flex;
  flex-wrap: wrap;
  gap: 0 16px;
  align-items: center;
}
.ios-beta-actions a {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  color: var(--ion-color-primary);
  font-size: 14px;
  text-decoration: none;
}
.ios-beta-actions .ios-beta-join {
  padding: 0 14px;
  border-radius: 10px;
  background: var(--ion-color-primary);
  color: var(--ion-color-primary-contrast, #fff);
  font-weight: 600;
}
.ios-beta-guide:hover {
  text-decoration: underline;
}
.ios-beta-close:hover {
  background: var(--cook-fill, #8882);
}
.ios-beta-card :focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 3px;
}
.ios-beta-note {
  grid-column: 2 / -1;
}
@media (max-width: 479px) {
  .ios-beta-actions,
  .ios-beta-note {
    grid-column: 1 / -1;
  }
}
</style>
