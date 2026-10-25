<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { iosVersion, links } from '../constants'
import { emojiOf, flyEmoji, prefersReducedMotion, recommend, stuffGroups, total } from '../landing'

const ingredients = stuffGroups.flatMap(group => group.items)

const selected = ref<string[]>(['番茄', '鸡蛋'])
const matches = computed(() => selected.value.length ? recommend({ stuff: selected.value }) : [])
const steam = computed(() => matches.value.slice(0, 4))
const steamKey = computed(() => selected.value.join('、') || 'empty')

const sky = ref<HTMLElement>()
const pot = ref<SVGSVGElement>()
const potBump = shallowRef(0)
const flightController = new AbortController()
onBeforeUnmount(() => flightController.abort())

// Animate only in response to ingredient selection.
function toggle(name: string, event: MouseEvent) {
  const adding = !selected.value.includes(name)
  selected.value = adding ? [...selected.value, name] : selected.value.filter(item => item !== name)
  const emoji = emojiOf(name)
  if (!adding || !emoji || !sky.value || !pot.value || prefersReducedMotion())
    return
  const potRect = pot.value.getBoundingClientRect()
  // Aim for the mouth of the pot.
  const mouth = new DOMRect(potRect.left + potRect.width * 0.45, potRect.top + potRect.height * 0.5, 0, 0)
  void flyEmoji(sky.value, emoji, (event.currentTarget as HTMLElement).getBoundingClientRect(), mouth, { size: 30, duration: 560, lift: 140, signal: flightController.signal })
    .then(() => {
      if (!flightController.signal.aborted)
        potBump.value++
    })
}

function missingText(missing: string[]) {
  return missing.length ? `还差${missing.join('、')}` : '食材齐了'
}
</script>

<template>
  <section ref="sky" class="sky" aria-labelledby="landing-title">
    <div class="sky-copy">
      <div class="app-id">
        <img class="app-icon" src="/brand/cook-app-icon.svg" alt="" width="64" height="64">
        <div>
          <p class="app-name">
            云食册 · 食用手册
          </p>
          <p class="app-status">
            <span class="status-dot" aria-hidden="true" />
            <span>网页版可用 · iOS 准备中</span>
          </p>
        </div>
      </div>
      <h1 id="landing-title" class="sky-title">
        有什么食材，<br>就做什么菜
      </h1>
      <p class="sky-lead">
        选几样冰箱里有的，从 {{ total }} 道菜谱里找做饭灵感。食用手册网页版打开即用，原生 iOS 版「云食册」把自己的厨房装进口袋。
      </p>
      <div class="sky-actions">
        <a class="btn btn-yolk vp-raw" :href="links.web" target="_self">打开食用手册网页版</a>
        <a class="btn btn-ghost" href="/guide/getting-started">阅读使用指南</a>
      </div>
      <p class="sky-note">
        基础做菜功能无需登录。iOS {{ iosVersion }} 尚未在 App Store 上架。<a href="#availability">查看版本与体验报名</a>
      </p>
    </div>

    <div class="pot-demo">
      <div class="steam" aria-hidden="true">
        <Transition name="steam" mode="out-in">
          <ol :key="steamKey" class="steam-list">
            <li v-if="!selected.length" class="cloud cloud-hint" style="--i: 0">
              先选几样食材
            </li>
            <li v-else-if="!steam.length" class="cloud cloud-hint" style="--i: 0">
              这几样凑不出一道菜，换一样试试
            </li>
            <li v-for="(match, index) in steam" :key="match.recipe.id ?? `${match.recipe.name}-${index}`" class="cloud" :style="{ '--i': index }">
              <span class="cloud-name">{{ match.recipe.name }}</span>
              <span class="cloud-meta">{{ missingText(match.missingStuff) }}</span>
            </li>
          </ol>
        </Transition>
      </div>

      <svg ref="pot" :key="potBump" class="pot" :class="{ 'is-bump': potBump > 0 }" viewBox="0 -40 100 96" role="img" :aria-label="`推荐 ${matches.length} 道`">
        <path d="M66 10 86.5 -30.5" class="pot-spoon" stroke-width="6" stroke-linecap="round" />
        <circle cx="87.5" cy="-32" r="5" class="pot-spoon-head" />
        <rect x="0" y="4" width="100" height="8" rx="4" class="pot-lid" />
        <path d="M8 15h84v17a16 16 0 0 1-16 16H24A16 16 0 0 1 8 32Z" class="pot-body" />
        <text x="50" y="34.5" text-anchor="middle" class="pot-count">
          推荐 {{ matches.length }} 道
        </text>
      </svg>
      <p class="sr-only" aria-live="polite">
        已选 {{ selected.length }} 样食材，推荐 {{ matches.length }} 道菜{{ steam.length ? `，比如${steam.map(m => m.recipe.name).join('、')}` : '' }}
      </p>

      <div class="chips" role="group" aria-label="选食材，试试能做什么">
        <button
          v-for="item in ingredients"
          :key="item.name"
          type="button"
          class="chip"
          :aria-pressed="selected.includes(item.name)"
          @click="toggle(item.name, $event)"
        >
          <span aria-hidden="true">{{ item.emoji }}</span>{{ item.name }}
        </button>
        <button v-if="selected.length" type="button" class="chip chip-clear" @click="selected = []">
          清空
        </button>
      </div>
      <p class="pot-caption">
        选食材试试：使用项目菜谱与推荐规则，默认家里有油和盐；结果包含还差食材的菜谱。
      </p>
      <noscript><p class="pot-caption">启用 JavaScript 后可切换食材，也可以直接打开网页版或阅读指南。</p></noscript>
    </div>
  </section>
</template>

<style scoped>
/* ---------- 首屏 ---------- */

.sky {
  position: relative;
  display: grid;
  gap: 40px;
  padding: 40px 24px 32px;
  border-radius: 36px;
  color: #fff;
  background: linear-gradient(225deg, #2563eb 0%, #1d4ed8 55%, #1e40af 100%);
  overflow: hidden;
}

.dark .sky {
  background:
    radial-gradient(120% 80% at 100% 0%, rgba(124, 192, 255, 0.16), transparent 60%),
    linear-gradient(225deg, #1e293b 0%, #0b1120 100%);
  box-shadow: inset 0 0 0 1px rgba(124, 192, 255, 0.14);
}

@media (min-width: 960px) {
  .sky {
    grid-template-columns: minmax(0, 1fr) minmax(0, 1.05fr);
    align-items: center;
    gap: 32px;
    padding: 40px 44px;
    min-height: 700px;
  }
}

/* 像 App Store 里的一行：图标、名字和内测状态 */
.app-id {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 28px;
}

.app-icon {
  flex: none;
  width: 52px;
  height: 52px;
  /* iOS 图标的连续圆角，22.37% */
  border-radius: 22.37%;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.28),
    0 12px 28px -10px rgba(8, 20, 60, 0.55);
}

.app-name {
  margin: 0;
  font-family: var(--cook-font-display);
  font-size: 20px;
  line-height: 1.2;
}

.app-status {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 4px 0 0;
  font-size: 13px;
  color: #fff;
}

.status-dot {
  position: relative;
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 0.5em;
  border-radius: 50%;
  background: var(--yolk);
}

.status-dot.is-live {
  background: #4ade80;
}

/* 一圈慢慢散开的光，表示「正在进行」 */
.status-dot::after {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: inherit;
}

@keyframes ping {
  70%,
  100% {
    transform: scale(2.6);
    opacity: 0;
  }
}

/* Keep the editorial heading independent of documentation heading ornaments. */
#landing-title.sky-title {
  margin: 0;
  padding: 0;
  border: 0;
  background: none;
  font-family: var(--cook-font-display);
  font-size: clamp(32px, 10.2vw, 40px);
  font-weight: 400;
  line-height: 1.12;
  letter-spacing: 0.02em;
  text-wrap: balance;
}

#landing-title.sky-title::before,
#landing-title.sky-title::after {
  display: none;
  content: none;
}

@media (min-width: 640px) {
  #landing-title.sky-title {
    font-size: clamp(48px, 4.5vw, 64px);
  }
}

.sky-lead {
  max-width: 30em;
  margin: 24px 0 0;
  font-size: 17px;
  line-height: 1.75;
  color: #fff;
}

.sky-actions,
.beta-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-top: 32px;
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 48px;
  padding: 0 24px;
  border-radius: 999px;
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
  transition:
    transform 0.15s ease,
    background-color 0.15s ease;
}

.btn:not(.is-pending):active {
  transform: scale(0.97);
}

.btn-yolk {
  background: var(--yolk);
  color: var(--ink);
}

.btn-yolk:not(.is-pending):hover {
  background: #fde047;
}

.btn-ghost {
  color: #fff;
  box-shadow: inset 0 0 0 1.5px rgba(255, 255, 255, 0.6);
}

.btn-ghost:hover {
  background: rgba(255, 255, 255, 0.12);
}

.btn-sky {
  background: var(--sky-2);
  color: #fff;
}

.btn-sky:not(.is-pending):hover {
  background: var(--sky-3);
}

.dark .btn-sky {
  background: #3b82f6;
}

.btn.is-pending {
  cursor: default;
  opacity: 0.72;
}

.sky-note {
  margin: 16px 0 0;
  font-size: 14px;
  color: #fff;
}

.sky-note a {
  color: #fff;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.sky a:focus-visible,
.sky button:focus-visible {
  outline: 3px solid var(--yolk);
  outline-offset: 3px;
}

/* ---------- 锅和蒸汽 ---------- */

.pot-demo {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.steam {
  width: 100%;
  min-height: 180px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.steam-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: center;
  gap: 28px 16px;
  width: 100%;
  margin: 0;
  padding: 24px 0 20px;
  list-style: none;
}

.cloud {
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 0.55em 0.75em 0.6em;
  border-radius: 999px;
  background: var(--cloud);
  color: var(--ink);
  font-size: 16px;
  line-height: 1.3;
  text-align: center;
  box-shadow: 0 10px 24px -14px rgba(15, 37, 71, 0.6);
  animation: rise 0.5s cubic-bezier(0.2, 0.8, 0.3, 1) both;
  animation-delay: calc((3 - var(--i)) * 70ms);
}

.cloud:nth-child(even) {
  transform: translateY(14px);
}

/* 云朵上面鼓起的两团 */
.cloud::before,
.cloud::after {
  content: '';
  position: absolute;
  z-index: -1;
  border-radius: 50%;
  background: inherit;
}

.cloud::before {
  width: 1.7em;
  height: 1.7em;
  top: -0.65em;
  left: 18%;
}

.cloud::after {
  width: 2.3em;
  height: 2.3em;
  top: -0.95em;
  right: 20%;
}

.cloud-name {
  font-family: var(--cook-font-display);
  font-size: 1.15em;
}

.cloud-meta {
  font-size: 12px;
  color: #3e5b86;
}

.cloud-hint {
  grid-column: 1 / -1;
  justify-self: center;
  font-family: var(--cook-font-display);
  font-size: 18px;
}

@keyframes rise {
  from {
    opacity: 0;
    translate: 0 36px;
    scale: 0.85;
  }
}

.steam-leave-active {
  transition:
    opacity 0.18s ease,
    translate 0.18s ease;
}

.steam-leave-to {
  opacity: 0;
  translate: 0 -16px;
}

.pot {
  width: min(250px, 78%);
  height: auto;
  margin-top: -6px;
  overflow: visible;
  transform-origin: 50% 90%;
}

/* 食材落进锅里时，锅轻轻颠一下 */
.pot.is-bump {
  animation: catch 0.4s cubic-bezier(0.3, 1.5, 0.5, 1);
}

@keyframes catch {
  35% {
    transform: scale(1.04, 0.95) translateY(3px);
  }
}

.pot-spoon {
  stroke: var(--yolk);
}

.pot-spoon-head {
  fill: var(--yolk);
}

.pot-lid,
.pot-body {
  fill: #fff;
}

.dark .pot-lid,
.dark .pot-body {
  fill: #7cc0ff;
}

/* SVG 用户单位，锅宽 100 */
.pot-count {
  font-family: var(--cook-font-display);
  font-size: 9.5px;
  fill: var(--sky-2);
}

.dark .pot-count {
  fill: #0b1120;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  max-width: 520px;
  margin-top: 12px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 44px;
  padding: 0 10px;
  border-radius: 999px;
  background: #1e40af;
  color: #fff;
  font-size: 14px;
  transition:
    background-color 0.15s ease,
    color 0.15s ease;
}

.chip:hover {
  background: #172e81;
}

.chip[aria-pressed='true'] {
  background: var(--yolk);
  color: var(--ink);
}

.chip-clear {
  background: transparent;
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.45);
}

.pot-caption {
  max-width: 28em;
  margin: 16px 0 0;
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
  color: #fff;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation: none !important;
    transition: none !important;
  }
}
</style>
