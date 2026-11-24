<script setup lang="ts">
import type { RecipeMatch } from '../../../../packages/cook/src/utils/recommend'
import type { StuffItem } from '../landing'
import { useData } from 'vitepress'
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { links } from '../constants'
import { emojiOf, prefersReducedMotion, thumbnail, total, tweenNumber } from '../landing'
import AppStoreBadge from './AppStoreBadge.vue'
import LandingIngredients from './LandingIngredients.vue'

const props = defineProps<{
  selected: readonly string[]
  ingredients: readonly StuffItem[]
  expanded: boolean
  recipes: readonly RecipeMatch[]
  matchCount: number
  cooking: boolean
  served: boolean
}>()

const emit = defineEmits<{ select: [name: string], expand: [], clear: [], cook: [] }>()
const { isDark } = useData()
const CloudKitchen = defineAsyncComponent(() => import('./CloudKitchen.vue'))

const greeting = shallowRef('今天也记得好好吃饭')
const displayMatchCount = shallowRef(props.matchCount)
let stopTween = () => {}

function resolveGreeting(hour: number) {
  if (hour >= 5 && hour < 10)
    return '早安 · 一口热乎早餐，唤醒慢慢苏醒的早晨'
  if (hour >= 10 && hour < 14)
    return '午安 · 忙里偷闲，吃一顿顺口的热饭'
  if (hour >= 14 && hour < 17)
    return '午后好 · 趁着阳光正好，提前想想今晚煮什么'
  if (hour >= 17 && hour < 21)
    return '傍晚好 · 回家开火，慰劳奔波了一天的自己'
  return '夜深了 · 一碗热腾腾的夜宵最能安抚肠胃'
}

onMounted(() => {
  greeting.value = resolveGreeting(new Date().getHours())
})

watch(() => props.matchCount, (nextCount) => {
  stopTween()
  if (prefersReducedMotion())
    displayMatchCount.value = nextCount
  else
    stopTween = tweenNumber(displayMatchCount.value, nextCount, value => displayMatchCount.value = value, 340)
})

onBeforeUnmount(() => stopTween())

const ticketNote = computed(() => {
  if (props.cooking)
    return '锅里咕嘟咕嘟响起来了，热气马上就到…'
  if (props.served)
    return '菜单出炉啦，挑一道最合心意的开动吧！'
  if (!props.recipes.length)
    return '厨房还空着，先挑几样顺眼的食材放上台面吧。'
  return '点一道菜，直接去看详细做法。'
})

const fallbackDishPool = ['🍲', '🥘', '🥣', '🥗', '🍽️', '🍳']

const dishIcons = computed(() => {
  const used = new Set<string>()
  return props.recipes.map((match) => {
    for (const name of match.recipe.stuff) {
      const icon = emojiOf(name)
      if (icon && !used.has(icon)) {
        used.add(icon)
        return icon
      }
    }
    const thumb = thumbnail(match.recipe)
    const icon = used.has(thumb) ? (fallbackDishPool.find(item => !used.has(item)) ?? thumb) : thumb
    used.add(icon)
    return icon
  })
})

function recipeLink(match: RecipeMatch) {
  return match.recipe.bv ? `https://www.bilibili.com/video/${match.recipe.bv}` : match.recipe.link || links.web
}
</script>

<template>
  <section id="kitchen" class="cloud-kitchen-hero" aria-labelledby="landing-title">
    <div class="kitchen-intro">
      <div class="intro-copy">
        <div class="status-row">
          <a class="status-pill vp-raw" href="#download">
            <span class="status-dot" aria-hidden="true" />
            <span>App Store 正式上架</span>
          </a>
          <span class="greeting-text">{{ greeting }}</span>
        </div>
        <h1 id="landing-title" class="kitchen-title">
          <span class="title-clause">手头有什么，</span><span class="title-clause">今晚就做什么。</span>
        </h1>
        <p class="kitchen-lead">
          不用等凑齐山珍海味，两枚鸡蛋、一颗番茄，也能端出一盘热气腾腾的温柔。先在云厨房点点食材，看看今天能做什么。
        </p>
      </div>

      <div class="intro-actions">
        <div class="cta-group">
          <AppStoreBadge size="lg" />
          <a class="kitchen-web-cta vp-raw" :href="links.web" target="_self">
            <span>打开网页版</span>
            <span class="cta-arrow" aria-hidden="true">↗</span>
          </a>
        </div>
        <p class="cta-trust">
          内置 <strong>{{ total }}</strong> 道家常菜谱 · 免登录 · 免费下载
        </p>
      </div>
    </div>

    <div class="kitchen-workbench">
      <div class="kitchen-world">
        <div class="kitchen-aura" :class="{ 'is-cooking': cooking }" aria-hidden="true" />
        <CloudKitchen
          class="kitchen-scene"
          :selected="selected"
          :cooking="cooking"
          :dark="isDark"
          @select="emit('select', $event)"
          @cook="emit('cook')"
        />
      </div>

      <aside class="recipe-ticket" :class="{ 'is-served': served, 'is-cooking': cooking }" aria-labelledby="ticket-title">
        <div class="ticket-clip" aria-hidden="true" />
        <div class="ticket-heading">
          <div class="ticket-title-row">
            <h2 id="ticket-title">
              {{ served ? '菜单出炉' : '今天能做' }}
            </h2>
            <Transition name="badge-pop">
              <span v-if="served" class="served-pill">出锅啦</span>
            </Transition>
          </div>
          <span>{{ selected.length }} 样食材</span>
        </div>
        <p class="ticket-note">
          {{ ticketNote }}
        </p>
        <div class="ticket-results" aria-live="polite">
          <a
            v-for="(match, index) in recipes"
            :key="match.recipe.id ?? match.recipe.name"
            class="ticket-recipe vp-raw"
            :href="recipeLink(match)"
            target="_blank"
            rel="noopener noreferrer"
          >
            <span class="ticket-dish" aria-hidden="true">{{ dishIcons[index] }}</span>
            <span class="ticket-recipe-copy">
              <strong>{{ match.recipe.name }}</strong>
              <small>{{ match.missingStuff.length ? `还差 ${match.missingStuff.join('、')}` : '食材齐了，随时开火' }}</small>
            </span>
            <span class="ticket-arrow" aria-hidden="true">↗</span>
          </a>
          <p v-if="!recipes.length" class="ticket-empty">
            厨房还空着，<br>点选下方或台面上的食材试试。
          </p>
        </div>
        <p class="ticket-total">
          共找到 <strong class="count-num">{{ displayMatchCount }}</strong> 道相关菜谱
        </p>
      </aside>
    </div>

    <div class="kitchen-inventory">
      <LandingIngredients
        variant="inventory"
        :selected="selected"
        :ingredients="ingredients"
        :expanded="expanded"
        @select="emit('select', $event)"
        @expand="emit('expand')"
        @clear="emit('clear')"
      />
      <button class="cook-button" :class="{ 'is-cooking': cooking }" type="button" :disabled="!recipes.length || cooking" @click="emit('cook')">
        <svg class="flame-icon" viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
          <path d="M13 2c1 5-3 6-2 10 1-2 3-2 4-5 2 3 5 6 5 10a8 8 0 0 1-16 0c0-4 3-7 5-10-1 4 0 5 1 6-1-5 3-7 3-11Z" />
        </svg>
        <span>{{ cooking ? '咕嘟咕嘟…' : served ? '再开一锅' : '开火试试' }}</span>
      </button>
    </div>
  </section>
</template>

<style scoped>
.cloud-kitchen-hero {
  padding: 24px 0 18px;
}

.kitchen-intro {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 28px;
  padding-inline: 8px;
}

.intro-copy {
  max-width: 680px;
}

.status-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 14px;
  margin-bottom: 14px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid rgba(10, 122, 90, 0.24);
  background: var(--cook-leaf-soft);
  color: var(--cook-leaf);
  font-size: 12px;
  font-weight: 650;
  text-decoration: none;
  transition:
    transform 0.2s ease,
    background-color 0.2s ease;
}

.status-pill:hover {
  transform: translateY(-1px);
  color: var(--cook-leaf);
}

.status-dot {
  position: relative;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: currentColor;
}

.status-dot::after {
  content: '';
  position: absolute;
  inset: -3px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.35;
  animation: pulse-ring 2.2s cubic-bezier(0.2, 0.8, 0.4, 1) infinite;
}

@keyframes pulse-ring {
  0% {
    transform: scale(0.7);
    opacity: 0.55;
  }
  75%,
  100% {
    transform: scale(1.9);
    opacity: 0;
  }
}

.greeting-text {
  color: var(--cook-hero-muted);
  font-size: 14px;
  line-height: 1.4;
}

.kitchen-title {
  margin: 0;
  padding: 0;
  border: 0;
  color: var(--cook-hero-text);
  font-family: var(--cook-font-display);
  font-size: clamp(34px, 4vw, 54px);
  font-weight: 700;
  line-height: 1.16;
  letter-spacing: -0.028em;
  text-wrap: balance;
}

.title-clause {
  display: inline-block;
}

.kitchen-lead {
  margin: 14px 0 0;
  color: var(--cook-hero-muted);
  font-size: 16px;
  line-height: 1.7;
  text-wrap: pretty;
}

.intro-actions {
  display: grid;
  justify-items: end;
  gap: 10px;
  flex-shrink: 0;
}

.cta-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
}

.kitchen-web-cta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 56px;
  padding: 0 20px;
  border-radius: 16px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
  box-shadow: var(--cook-glass-shadow);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: var(--cook-hero-text);
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
  transition:
    transform 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    border-color 0.22s ease,
    color 0.22s ease;
}

.kitchen-web-cta:hover {
  transform: translateY(-2px);
  border-color: var(--cook-leaf);
  color: var(--cook-leaf);
}

.cta-arrow {
  transition: transform 0.2s ease;
}

.kitchen-web-cta:hover .cta-arrow {
  transform: translate(2px, -2px);
}

.cta-trust {
  color: var(--cook-hero-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.cta-trust strong {
  color: var(--cook-hero-text);
  font-weight: 650;
}

.kitchen-workbench {
  display: grid;
  grid-template-columns: minmax(0, 760px) 264px;
  justify-content: center;
  gap: 20px;
  align-items: center;
  margin-top: 8px;
}

.kitchen-world {
  position: relative;
  min-width: 0;
  height: 468px;
}

.kitchen-aura {
  position: absolute;
  inset: 8% 12% 14%;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(253, 214, 152, 0.42) 0%, rgba(169, 211, 250, 0.24) 55%, transparent 76%);
  filter: blur(28px);
  transition:
    background 0.55s ease,
    transform 0.55s ease,
    opacity 0.55s ease;
  pointer-events: none;
}

.dark .kitchen-aura {
  background: radial-gradient(circle, rgba(251, 168, 72, 0.18) 0%, rgba(124, 192, 255, 0.14) 55%, transparent 76%);
}

.kitchen-aura.is-cooking {
  background: radial-gradient(circle, rgba(249, 115, 22, 0.36) 0%, rgba(250, 204, 21, 0.24) 52%, transparent 74%);
  transform: scale(1.06);
}

.kitchen-scene {
  position: relative;
  z-index: 1;
  height: 100%;
}

.recipe-ticket {
  position: relative;
  align-self: start;
  margin-top: 10px;
  padding: 24px 20px 18px;
  background: var(--cook-ticket-background);
  color: var(--cook-ticket-text);
  box-shadow:
    0 20px 38px -22px rgba(48, 36, 20, 0.24),
    0 4px 12px -4px rgba(48, 36, 20, 0.08);
  clip-path: polygon(
    0 4px,
    5% 0,
    11% 3px,
    17% 0,
    23% 3px,
    29% 0,
    35% 3px,
    41% 0,
    47% 3px,
    53% 0,
    59% 3px,
    65% 0,
    71% 3px,
    77% 0,
    83% 3px,
    89% 0,
    95% 3px,
    100% 0,
    100% calc(100% - 3px),
    95% 100%,
    89% calc(100% - 3px),
    83% 100%,
    77% calc(100% - 3px),
    71% 100%,
    65% calc(100% - 3px),
    59% 100%,
    53% calc(100% - 3px),
    47% 100%,
    41% calc(100% - 3px),
    35% 100%,
    29% calc(100% - 3px),
    23% 100%,
    17% calc(100% - 3px),
    11% 100%,
    5% calc(100% - 3px),
    0 100%
  );
  transform: rotate(0.8deg);
  transition:
    transform 0.35s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.35s ease;
}

.dark .recipe-ticket {
  transform: rotate(0.4deg);
}

.recipe-ticket:hover {
  transform: rotate(0.2deg) translateY(-2px);
}

.ticket-clip {
  width: 40px;
  height: 9px;
  margin: -16px auto 16px;
  border-radius: 3px;
  background: #caad7f;
  box-shadow: inset 0 2px #e7d4b2;
}

.ticket-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
}

.ticket-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ticket-heading h2 {
  margin: 0;
  padding: 0;
  border: 0;
  font-family: var(--cook-font-display);
  font-size: 20px;
  font-weight: 700;
}

.served-pill {
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(249, 115, 22, 0.14);
  color: var(--cook-flame-2);
  font-size: 12px;
  font-weight: 700;
}

.ticket-heading > span {
  color: var(--cook-ticket-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.ticket-note {
  margin: 6px 0 12px;
  color: var(--cook-ticket-muted);
  font-size: 12px;
  line-height: 1.5;
}

.ticket-results {
  position: relative;
  border-block: 1px dashed var(--cook-panel-border);
}

.ticket-recipe {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 4px;
  border-radius: 10px;
  color: inherit;
  text-decoration: none;
  transition:
    background-color 0.2s ease,
    transform 0.2s ease;
}

.ticket-recipe:hover {
  background: rgba(224, 159, 31, 0.1);
  transform: translateX(2px);
}

.ticket-recipe + .ticket-recipe {
  border-top: 1px solid var(--cook-panel-border);
}

.ticket-dish {
  display: grid;
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  place-items: center;
  border-radius: 10px;
  background: var(--cook-ticket-dish);
  font-size: 22px;
}

.ticket-recipe-copy {
  min-width: 0;
}

.ticket-recipe strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ticket-recipe small {
  display: block;
  margin-top: 3px;
  color: var(--cook-ticket-muted);
  font-size: 12px;
  line-height: 1.45;
}

.ticket-arrow {
  flex-shrink: 0;
  margin-left: auto;
  color: var(--cook-ticket-muted);
  font-size: 14px;
  transition:
    transform 0.2s ease,
    color 0.2s ease;
}

.ticket-recipe:hover .ticket-arrow {
  color: var(--cook-flame-2);
  transform: translate(2px, -2px);
}

.ticket-empty {
  padding: 30px 0;
  color: var(--cook-ticket-muted);
  font-size: 14px;
  line-height: 1.75;
}

.ticket-total {
  margin: 12px 0 0;
  color: var(--cook-ticket-muted);
  font-size: 12px;
  line-height: 1.6;
}

.count-num {
  color: var(--cook-ticket-text);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.kitchen-inventory {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  width: fit-content;
  max-width: 100%;
  margin: 12px auto 0;
  padding: 14px 18px 10px;
  border: 1px solid var(--cook-glass-border);
  border-radius: 26px;
  background: var(--cook-panel-background);
  backdrop-filter: blur(18px) saturate(1.4);
  -webkit-backdrop-filter: blur(18px) saturate(1.4);
  box-shadow:
    0 6px 0 var(--cook-dock-shadow),
    0 20px 44px -22px rgba(48, 36, 20, 0.22);
}

.cook-button {
  position: relative;
  display: flex;
  min-width: 128px;
  min-height: 54px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  gap: 7px;
  align-self: center;
  padding: 10px 18px;
  border: 1px solid #eab676;
  border-radius: 16px;
  background: #fff0d8;
  box-shadow:
    0 3px 0 #e2b887,
    0 10px 20px -12px rgba(180, 83, 9, 0.3);
  color: #b45309;
  font-family: var(--cook-font-display);
  font-size: 16px;
  font-weight: 700;
  cursor: pointer;
  transition:
    transform 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.2s ease,
    background 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.dark .cook-button {
  border-color: #9a5318;
  background: #342920;
  box-shadow:
    0 3px 0 #5b3112,
    0 10px 20px -12px rgba(0, 0, 0, 0.5);
  color: #fdba74;
}

.cook-button:not(:disabled):hover,
.cook-button.is-cooking {
  transform: translateY(-2px);
  border-color: #c2410c;
  background: linear-gradient(155deg, #fb923c 0%, #ea580c 100%);
  box-shadow:
    0 4px 0 #c2410c,
    0 16px 28px -12px rgba(234, 88, 12, 0.58),
    inset 0 1.5px 0 rgba(255, 255, 255, 0.42);
  color: #ffffff;
}

.cook-button:disabled {
  opacity: 0.55;
  cursor: default;
}

.cook-button:not(:disabled):active {
  transform: translateY(2px);
  box-shadow: 0 1px 0 #c2410c;
}

.cook-button.is-cooking .flame-icon {
  animation: flame-flicker 0.55s ease-in-out infinite alternate;
}

@keyframes flame-flicker {
  from {
    transform: scale(0.96) rotate(-4deg);
  }
  to {
    transform: scale(1.1) rotate(4deg);
  }
}

.is-served .ticket-heading h2 {
  color: var(--cook-flame-2);
}

.badge-pop-enter-active {
  transition:
    transform 0.3s cubic-bezier(0.3, 1.5, 0.5, 1),
    opacity 0.2s ease;
}

.badge-pop-enter-from {
  opacity: 0;
  transform: scale(0.7);
}

@media (min-width: 960px) {
  .kitchen-scene {
    transform: translateY(-16px);
  }
  .kitchen-inventory {
    margin-top: -78px;
  }
}

@media (max-width: 1080px) and (min-width: 960px) {
  .kitchen-intro {
    flex-direction: column;
    align-items: flex-start;
    gap: 18px;
  }
  .intro-actions {
    justify-items: start;
  }
  .cta-group {
    justify-content: flex-start;
  }
}

@media (max-width: 959px) {
  .kitchen-intro {
    flex-direction: column;
    align-items: flex-start;
    gap: 18px;
  }
  .intro-actions {
    justify-items: start;
  }
  .cta-group {
    justify-content: flex-start;
  }
  .kitchen-workbench {
    grid-template-columns: minmax(0, 1fr) 232px;
    gap: 10px;
  }
  .kitchen-world {
    height: 400px;
  }
  .kitchen-inventory {
    gap: 16px;
    padding: 14px 14px 10px;
  }
  .cook-button {
    min-width: 118px;
    padding-inline: 14px;
  }
}

@media (max-width: 639px) {
  .cloud-kitchen-hero {
    display: flex;
    flex-direction: column;
    padding-top: 12px;
  }
  .kitchen-intro {
    padding: 0;
    gap: 14px;
  }
  .status-row {
    margin-bottom: 10px;
  }
  .greeting-text {
    display: none;
  }
  .kitchen-title {
    font-size: clamp(26px, 7.2vw, 34px);
  }
  .kitchen-lead {
    margin-top: 10px;
    font-size: 16px;
    line-height: 1.65;
  }
  .intro-actions {
    width: 100%;
    justify-items: stretch;
  }
  .cta-group {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 10px;
    width: 100%;
  }
  .kitchen-web-cta {
    min-height: 54px;
    padding-inline: 14px;
    font-size: 14px;
  }
  .kitchen-workbench {
    display: contents;
  }
  .kitchen-world {
    order: 1;
    width: calc(100% + 24px);
    height: 268px;
    margin-inline: -12px;
  }
  .kitchen-inventory {
    order: 2;
    flex-direction: column;
    gap: 10px;
    width: 100%;
    margin-top: 4px;
    padding: 14px;
    border-radius: 20px;
  }
  .cook-button {
    width: 100%;
    min-height: 48px;
    align-self: stretch;
  }
  .recipe-ticket {
    order: 3;
    width: 100%;
    margin-top: 16px;
    transform: none;
  }
  .dark .recipe-ticket,
  .recipe-ticket:hover {
    transform: none;
  }
  .ticket-recipe {
    padding-block: 12px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .status-dot::after,
  .cook-button.is-cooking .flame-icon {
    animation: none;
  }
  .status-pill,
  .kitchen-web-cta,
  .cta-arrow,
  .kitchen-aura,
  .recipe-ticket,
  .ticket-recipe,
  .ticket-arrow,
  .cook-button,
  .badge-pop-enter-active {
    transition: none;
  }
  .recipe-ticket:hover,
  .ticket-recipe:hover,
  .cook-button:not(:disabled):hover,
  .kitchen-web-cta:hover {
    transform: none;
  }
}
</style>
