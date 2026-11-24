<script setup lang="ts">
import { links } from '../constants'

withDefaults(defineProps<{
  size?: 'sm' | 'md' | 'lg'
  href?: string
  sublabel?: string
  label?: string
}>(), {
  size: 'md',
  href: links.appStore,
  sublabel: 'App Store 免费下载',
  label: '下载云食册',
})
</script>

<template>
  <a
    class="app-store-badge vp-raw"
    :class="`is-${size}`"
    :href="href"
    target="_blank"
    rel="noopener noreferrer"
  >
    <svg class="apple-mark" viewBox="0 0 24 24" aria-hidden="true">
      <path
        fill="currentColor"
        d="M18.71 19.5c-.83 1.24-1.71 2.45-3.05 2.47-1.34.03-1.77-.79-3.29-.79-1.53 0-2 .77-3.27.82-1.31.05-2.3-1.32-3.14-2.53C4.25 17 2.94 12.45 4.7 9.39c.87-1.52 2.43-2.48 4.12-2.51 1.28-.02 2.5.87 3.29.87.78 0 2.26-1.07 3.81-.91.65.03 2.47.26 3.64 1.98-.09.06-2.17 1.28-2.15 3.81.03 3.02 2.65 4.03 2.68 4.04-.03.07-.42 1.44-1.38 2.83M13 3.5c.73-.83 1.94-1.46 2.94-1.5.13 1.17-.34 2.35-1.04 3.19-.69.85-1.83 1.51-2.95 1.42-.15-1.15.41-2.35 1.05-3.11Z"
      />
    </svg>
    <span v-if="size === 'sm'" class="badge-compact-label">{{ label }}</span>
    <span v-else class="badge-copy">
      <span class="badge-sub">{{ sublabel }}</span>
      <span class="badge-title">{{ label }}</span>
    </span>
    <svg v-if="size !== 'sm'" class="badge-arrow" viewBox="0 0 20 20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M5 10h10M11 6l4 4-4 4" />
    </svg>
  </a>
</template>

<style scoped>
.app-store-badge {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 11px;
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.16);
  background: linear-gradient(165deg, #1f2937 0%, #0f172a 100%);
  color: #fff;
  text-decoration: none;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.22),
    0 12px 26px -12px rgba(15, 23, 42, 0.58),
    0 3px 0 #090e1a;
  transition:
    transform 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.22s ease,
    background-color 0.22s ease;
  cursor: pointer;
}

.app-store-badge::after {
  content: '';
  position: absolute;
  inset: -40% auto -40% -70%;
  width: 55%;
  background: linear-gradient(110deg, transparent 10%, rgba(255, 255, 255, 0.22) 50%, transparent 90%);
  transform: translateX(-100%) skewX(-18deg);
  transition: transform 0.65s cubic-bezier(0.22, 1, 0.36, 1);
  pointer-events: none;
}

.app-store-badge:hover {
  transform: translateY(-2px);
  color: #fff;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.28),
    0 18px 34px -14px rgba(15, 23, 42, 0.62),
    0 4px 0 #090e1a;
}

.app-store-badge:hover::after {
  transform: translateX(360%) skewX(-18deg);
}

.app-store-badge:active {
  transform: translateY(1px) scale(0.98);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.14),
    0 6px 14px -8px rgba(15, 23, 42, 0.5),
    0 1px 0 #090e1a;
}

.app-store-badge:focus-visible {
  outline: 3px solid var(--vp-c-brand-1);
  outline-offset: 4px;
}

.app-store-badge.is-sm {
  min-height: 36px;
  padding: 6px 13px;
  gap: 6px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.22),
    0 6px 16px -8px rgba(15, 23, 42, 0.45);
}

.app-store-badge.is-sm .apple-mark {
  width: 14px;
  height: 14px;
}

.app-store-badge.is-md {
  min-height: 52px;
  padding: 9px 18px;
}

.app-store-badge.is-lg {
  min-height: 56px;
  padding: 10px 20px 10px 18px;
}

.apple-mark {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
}

.badge-copy {
  display: grid;
  gap: 1px;
  text-align: left;
  line-height: 1.2;
}

.badge-sub {
  font-size: 11px;
  font-weight: 500;
  opacity: 0.78;
  letter-spacing: 0.01em;
}

.badge-title {
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.badge-arrow {
  width: 16px;
  height: 16px;
  margin-left: 2px;
  opacity: 0.72;
  transition:
    transform 0.22s ease,
    opacity 0.22s ease;
}

.app-store-badge:hover .badge-arrow {
  transform: translateX(3px);
  opacity: 1;
}

.dark .app-store-badge {
  border-color: rgba(255, 255, 255, 0.75);
  background: linear-gradient(165deg, #ffffff 0%, #e9eef8 100%);
  color: #0f172a;
  box-shadow:
    inset 0 1px 0 #ffffff,
    0 14px 30px -14px rgba(0, 0, 0, 0.65),
    0 3px 0 #cbd5e1;
}

.dark .app-store-badge::after {
  background: linear-gradient(110deg, transparent 10%, rgba(37, 99, 235, 0.14) 50%, transparent 90%);
}

.dark .app-store-badge:hover {
  color: #0f172a;
  box-shadow:
    inset 0 1px 0 #ffffff,
    0 20px 36px -14px rgba(124, 192, 255, 0.35),
    0 4px 0 #cbd5e1;
}

.dark .app-store-badge:active {
  box-shadow:
    0 6px 14px -8px rgba(0, 0, 0, 0.55),
    0 1px 0 #cbd5e1;
}

.dark .app-store-badge.is-sm {
  box-shadow:
    inset 0 1px 0 #ffffff,
    0 6px 16px -8px rgba(0, 0, 0, 0.45);
}

@media (prefers-reduced-motion: reduce) {
  .app-store-badge,
  .app-store-badge::after,
  .badge-arrow {
    transition: none;
  }
  .app-store-badge:hover {
    transform: none;
  }
}
</style>
