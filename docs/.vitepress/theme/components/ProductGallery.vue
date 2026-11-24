<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, shallowRef } from 'vue'
import FeatureBento from './FeatureBento.vue'
import PhoneDemo from './PhoneDemo.vue'

const screens = [
  { file: '2-cook-selected', label: '食材快选', alt: '做菜页：已选土豆、番茄和鸡蛋，底部按钮显示能做 239 道' },
  { file: '4-results', label: '即时匹配', alt: '能做的菜列表：6 阶番茄炒蛋、番茄炒蛋、酱土豆' },
  { file: '5-detail', label: '菜谱详情', alt: '番茄炒蛋详情：已有食材、做法和标签、去 B 站看做法' },
  { file: '8-random', label: '今天吃什么', alt: '今天吃什么：随机抽到胡萝卜炒肉' },
]

const lightboxIndex = shallowRef<number | null>(null)
const currentShot = computed(() => lightboxIndex.value === null ? null : screens[lightboxIndex.value] ?? null)

function openShot(index: number) {
  lightboxIndex.value = index
}

function closeLightbox() {
  lightboxIndex.value = null
}

function stepShot(delta: number) {
  if (lightboxIndex.value === null)
    return
  lightboxIndex.value = (lightboxIndex.value + delta + screens.length) % screens.length
}

function onKeydown(event: KeyboardEvent) {
  if (lightboxIndex.value === null)
    return
  if (event.key === 'Escape')
    closeLightbox()
  else if (event.key === 'ArrowLeft')
    stepShot(-1)
  else if (event.key === 'ArrowRight')
    stepShot(1)
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div>
    <section id="screens" class="screens" aria-labelledby="screens-title">
      <div class="section-head" data-reveal>
        <span class="section-eyebrow">原生 iOS 体验</span>
        <h2 id="screens-title" class="section-title">
          把一座温柔的小厨房，装进口袋
        </h2>
        <p class="section-lead">
          还是你熟悉的找菜默契，换成了 iPhone 与 iPad 上顺手、轻盈又安心的样子。点击任意章节，看看它如何陪你做决定。
        </p>
      </div>

      <div class="demo-block" data-reveal style="--reveal-delay: 80ms">
        <PhoneDemo />
      </div>

      <figure class="shots" data-reveal style="--reveal-delay: 100ms">
        <ul class="shot-row" tabindex="0" aria-label="云食册 iOS 实机界面截图，可横向滚动或点击放大">
          <li v-for="(screen, index) in screens" :key="screen.file" class="shot">
            <button
              type="button"
              class="shot-card"
              :aria-label="`放大查看：${screen.label}，${screen.alt}`"
              @click="openShot(index)"
            >
              <span class="shot-frame">
                <img class="shot-light" :src="`/ios/light-${screen.file}.webp`" :alt="screen.alt" width="640" height="1392" loading="lazy" decoding="async">
                <img class="shot-dark" :src="`/ios/dark-${screen.file}.webp`" :alt="screen.alt" width="640" height="1392" loading="lazy" decoding="async">
              </span>
              <span class="shot-label">{{ screen.label }}</span>
            </button>
          </li>
        </ul>
        <figcaption>云食册 iOS 实机界面 · 自动适配浅色与深色模式 · 点击可放大查看</figcaption>
      </figure>
    </section>

    <section id="highlights" class="app-only" aria-labelledby="app-only-title">
      <div class="section-head" data-reveal>
        <span class="section-eyebrow">为每次开火精心打磨</span>
        <h2 id="app-only-title" class="section-title">
          从开火到收尾，每一步都顺手
        </h2>
        <p class="section-lead">
          把云食册装进手机，离线翻菜、跟着工作台下厨、管好冰箱余粮，一切都轻轻接住。
        </p>
      </div>
      <FeatureBento />
    </section>

    <div
      v-if="currentShot"
      class="shot-lightbox"
      role="dialog"
      aria-modal="true"
      :aria-label="currentShot.label"
      @click.self="closeLightbox"
    >
      <div class="lightbox-panel">
        <button type="button" class="lightbox-close" aria-label="关闭大图" @click="closeLightbox">
          ×
        </button>
        <button type="button" class="lightbox-nav is-prev" aria-label="上一张" @click="stepShot(-1)">
          ‹
        </button>
        <div class="lightbox-frame">
          <img class="shot-light" :src="`/ios/light-${currentShot.file}.webp`" :alt="currentShot.alt" width="640" height="1392">
          <img class="shot-dark" :src="`/ios/dark-${currentShot.file}.webp`" :alt="currentShot.alt" width="640" height="1392">
        </div>
        <button type="button" class="lightbox-nav is-next" aria-label="下一张" @click="stepShot(1)">
          ›
        </button>
        <p class="lightbox-caption">
          <strong>{{ currentShot.label }}</strong> · {{ currentShot.alt }}
        </p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.demo-block {
  margin-top: 38px;
}

.shots {
  margin: 76px 0 0;
}

.shot-row {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(168px, 1fr);
  gap: 18px;
  margin: 0;
  padding: 6px 2px 14px;
  list-style: none;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
}

.shot-row:focus-visible {
  outline: 3px solid var(--vp-c-brand-1);
  outline-offset: 4px;
}

@media (min-width: 960px) {
  .shot-row {
    max-width: 840px;
    margin: 0 auto;
    padding: 6px 0 0;
    overflow: visible;
  }
}

.shot {
  margin: 0;
  scroll-snap-align: start;
}

.shot-card {
  display: grid;
  gap: 10px;
  width: 100%;
  padding: 0;
  text-align: center;
  cursor: zoom-in;
}

.shot-frame {
  display: block;
  aspect-ratio: 640 / 1392;
  padding: 5px;
  border-radius: 28px;
  background: #0f172a;
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.14),
    0 20px 40px -24px rgba(15, 23, 42, 0.45);
  transition:
    transform 0.3s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.3s ease;
}

.dark .shot-frame {
  background: #1e293b;
}

.shot-card:hover .shot-frame {
  transform: translateY(-6px) scale(1.015);
  box-shadow:
    inset 0 0 0 1px rgba(255, 255, 255, 0.22),
    0 28px 50px -24px rgba(37, 99, 235, 0.35);
}

.shot-frame img {
  margin: 0;
  width: 100%;
  height: 100%;
  border-radius: 23px;
  object-fit: cover;
}

.shot-label {
  justify-self: center;
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
  color: var(--cook-hero-muted);
  font-size: 12.5px;
  font-weight: 600;
  transition:
    color 0.2s ease,
    border-color 0.2s ease;
}

.shot-card:hover .shot-label {
  border-color: var(--sky-2);
  color: var(--cook-hero-text);
}

.shot-dark,
.dark .shot-light {
  display: none;
}

.dark .shot-dark {
  display: block;
}

.shots figcaption {
  margin-top: 16px;
  font-size: 13px;
  text-align: center;
  color: var(--cook-hero-muted);
}

/* Lightbox Modal */
.shot-lightbox {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.78);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.lightbox-panel {
  position: relative;
  display: grid;
  justify-items: center;
  gap: 14px;
  max-width: min(92vw, 480px);
}

.lightbox-frame {
  width: min(76vw, 330px);
  aspect-ratio: 640 / 1392;
  padding: 7px;
  border-radius: 36px;
  background: #0f172a;
  box-shadow:
    inset 0 0 0 1.5px rgba(255, 255, 255, 0.22),
    0 32px 72px rgba(0, 0, 0, 0.55);
}

.lightbox-frame img {
  width: 100%;
  height: 100%;
  border-radius: 29px;
  object-fit: cover;
}

.lightbox-close,
.lightbox-nav {
  position: absolute;
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.2);
  background: rgba(15, 23, 42, 0.7);
  color: #fff;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  transition:
    background-color 0.2s ease,
    transform 0.2s ease;
}

.lightbox-close:hover,
.lightbox-nav:hover {
  background: rgba(37, 99, 235, 0.9);
}

.lightbox-close {
  top: -10px;
  right: -10px;
}

.lightbox-nav {
  top: 44%;
  transform: translateY(-50%);
}

.lightbox-nav.is-prev {
  left: -56px;
}

.lightbox-nav.is-next {
  right: -56px;
}

.lightbox-caption {
  color: #f1f5f9;
  font-size: 13.5px;
  line-height: 1.5;
  text-align: center;
}

@media (max-width: 639px) {
  .lightbox-nav.is-prev {
    left: 4px;
  }
  .lightbox-nav.is-next {
    right: 4px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .shot-frame,
  .shot-label,
  .lightbox-close,
  .lightbox-nav {
    transition: none;
  }
  .shot-card:hover .shot-frame {
    transform: none;
  }
}
</style>
