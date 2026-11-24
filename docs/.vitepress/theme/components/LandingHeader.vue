<script setup lang="ts">
import { useData } from 'vitepress'
import { inject, onBeforeUnmount, onMounted, shallowRef } from 'vue'
import AppStoreBadge from './AppStoreBadge.vue'

const { isDark } = useData()
const toggleAppearance = inject('toggle-appearance', () => {
  isDark.value = !isDark.value
})

const scrolled = shallowRef(false)
const hydrated = shallowRef(false)

function onScroll() {
  scrolled.value = window.scrollY > 24
}

onMounted(() => {
  hydrated.value = true
  onScroll()
  window.addEventListener('scroll', onScroll, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <header class="landing-header" :class="{ 'is-scrolled': scrolled }">
    <a class="landing-brand" href="/" aria-label="云食册首页">
      <img src="/brand/cook-favicon.svg" width="34" height="34" alt="">
      <span class="brand-name">云食册</span>
      <span class="brand-sub">食用手册</span>
    </a>
    <nav class="landing-nav" aria-label="产品导航">
      <a href="#kitchen">云厨房</a>
      <a href="#screens">交互演示</a>
      <a href="#highlights">亮点</a>
      <a href="#download">下载</a>
      <a href="/guide/getting-started">使用指南</a>
    </nav>
    <div class="landing-header-actions">
      <button
        class="appearance-toggle"
        type="button"
        role="switch"
        :aria-checked="hydrated ? isDark : false"
        :aria-label="hydrated && isDark ? '切换到浅色模式' : '切换到深色模式'"
        @click="toggleAppearance"
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" aria-hidden="true">
          <g class="icon-sun"><circle cx="12" cy="12" r="4" /><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5" /></g>
          <path class="icon-moon" d="M20.5 13.2A8.5 8.5 0 0 1 10.8 3.5a8.5 8.5 0 1 0 9.7 9.7Z" />
        </svg>
      </button>
      <AppStoreBadge size="sm" label="下载 iOS 版" />
    </div>
  </header>
</template>

<style scoped>
.landing-header {
  position: sticky;
  top: 12px;
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 20px;
  min-height: 64px;
  margin-top: 8px;
  padding: 8px 16px;
  border: 1px solid transparent;
  border-radius: 999px;
  transition:
    background-color 0.28s ease,
    border-color 0.28s ease,
    box-shadow 0.28s ease,
    backdrop-filter 0.28s ease;
}

.landing-header.is-scrolled {
  border-color: rgba(212, 196, 173, 0.6);
  background: rgba(255, 252, 246, 0.97);
  box-shadow: 0 12px 30px -16px rgba(58, 42, 22, 0.18);
  backdrop-filter: blur(20px) saturate(1.3);
  -webkit-backdrop-filter: blur(20px) saturate(1.3);
}

.dark .landing-header.is-scrolled {
  border-color: rgba(148, 178, 214, 0.24);
  background: rgba(20, 29, 46, 0.97);
  box-shadow: 0 14px 36px -14px rgba(0, 0, 0, 0.68);
}

.landing-brand {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: 9px;
  color: var(--cook-hero-text);
  font-family: var(--cook-font-body);
  font-size: 20px;
  font-weight: 700;
  text-decoration: none;
}

.landing-brand img {
  width: 32px;
  height: 32px;
  margin: 0;
  border-radius: 9px;
  box-shadow: 0 4px 12px -4px rgba(10, 122, 90, 0.35);
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1);
}

.landing-brand:hover img {
  transform: rotate(-6deg) scale(1.06);
}

.brand-sub {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--cook-action-secondary);
  color: var(--cook-hero-muted);
  font-size: 12px;
  font-weight: 600;
}

.landing-nav {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-left: auto;
  color: var(--cook-hero-muted);
  font-size: 14px;
  font-weight: 500;
}

.landing-nav a {
  position: relative;
  padding: 6px 2px;
  color: inherit;
  text-decoration: none;
  transition: color 0.2s ease;
}

.landing-nav a:hover {
  color: var(--cook-leaf);
}

.landing-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

@media (min-width: 820px) {
  .landing-header-actions {
    margin-left: 12px;
  }
}

.appearance-toggle {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 50%;
  color: var(--cook-hero-muted);
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    color 0.2s ease,
    transform 0.25s cubic-bezier(0.22, 1, 0.36, 1);
}

.appearance-toggle:hover {
  background: var(--cook-action-secondary);
  color: var(--cook-hero-text);
  transform: rotate(16deg);
}

.appearance-toggle:focus:not(:focus-visible) {
  outline: none;
}

.icon-sun,
.dark .icon-moon {
  display: none;
}

.dark .icon-sun {
  display: block;
}

@media (max-width: 819px) {
  .landing-nav {
    display: none;
  }
}

@media (max-width: 639px) {
  .landing-header {
    top: 8px;
    gap: 10px;
    min-height: 58px;
    padding: 6px 10px;
  }
  .brand-sub {
    display: none;
  }
  .landing-brand {
    gap: 7px;
    font-size: 16px;
  }
  .landing-brand img {
    width: 28px;
    height: 28px;
    border-radius: 8px;
  }
  .landing-header-actions {
    gap: 6px;
  }
  .appearance-toggle {
    width: 34px;
    height: 34px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .landing-header,
  .landing-brand img,
  .appearance-toggle {
    transition: none;
  }
  .landing-brand:hover img,
  .appearance-toggle:hover {
    transform: none;
  }
}
</style>
