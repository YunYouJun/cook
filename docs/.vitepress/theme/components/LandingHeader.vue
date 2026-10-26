<script setup lang="ts">
import { useData } from 'vitepress'
import { inject } from 'vue'

const { isDark } = useData()
const toggleAppearance = inject('toggle-appearance', () => {
  isDark.value = !isDark.value
})
</script>

<template>
  <header class="landing-header">
    <a class="landing-brand" href="/" aria-label="云食册首页">
      <img src="/brand/cook-app-icon.svg" width="36" height="36" alt="">
      <span>云食册</span>
    </a>
    <nav class="landing-nav" aria-label="产品导航">
      <a href="#screens">功能</a>
      <a href="/guide/getting-started">使用指南</a>
    </nav>
    <div class="landing-header-actions">
      <button
        class="appearance-toggle"
        type="button"
        role="switch"
        :aria-checked="isDark"
        :aria-label="isDark ? '切换到浅色模式' : '切换到深色模式'"
        @click="toggleAppearance"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true">
          <g v-if="isDark"><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" /></g>
          <path v-else d="M20.5 13.2A8.5 8.5 0 0 1 10.8 3.5a8.5 8.5 0 1 0 9.7 9.7Z" />
        </svg>
      </button>
    </div>
  </header>
</template>

<style scoped>
.landing-header {
  display: flex;
  align-items: center;
  gap: 32px;
  min-height: 88px;
  padding: 12px 8px;
}
.landing-brand {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: 10px;
  color: var(--cook-hero-text);
  font-family: var(--cook-font-body);
  font-size: 21px;
  font-weight: 650;
  text-decoration: none;
}
.landing-brand img {
  width: 34px;
  height: 34px;
  margin: 0;
  border-radius: 9px;
}
.landing-nav {
  display: flex;
  gap: 28px;
  margin-left: auto;
  color: var(--cook-hero-muted);
  font-size: 14px;
}
.landing-nav a {
  text-decoration: none;
}
.landing-nav a:hover {
  color: var(--sky-2);
}
.landing-header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}
.appearance-toggle {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  color: var(--cook-hero-muted);
  font-size: 18px;
  cursor: pointer;
}
.appearance-toggle:hover {
  background: var(--cook-action-secondary);
}
@media (max-width: 639px) {
  .landing-header {
    gap: 12px;
    min-height: 76px;
    padding-inline: 0;
  }
  .landing-nav {
    display: none;
  }
  .landing-brand {
    gap: 7px;
    font-size: 17px;
  }
  .landing-brand img {
    width: 30px;
    height: 30px;
  }
  .landing-header-actions {
    gap: 4px;
  }
  .appearance-toggle {
    width: 30px;
  }
}
</style>
