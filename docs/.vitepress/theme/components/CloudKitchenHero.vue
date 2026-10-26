<script setup lang="ts">
import type { RecipeMatch } from '../../../../packages/cook/src/utils/recommend'
import type { StuffItem } from '../landing'
import { useData } from 'vitepress'
import { defineAsyncComponent } from 'vue'
import { links } from '../constants'
import LandingIngredients from './LandingIngredients.vue'

defineProps<{ selected: readonly string[], ingredients: readonly StuffItem[], expanded: boolean, recipes: readonly RecipeMatch[], matchCount: number, cooking: boolean, served: boolean }>()
const emit = defineEmits<{ select: [name: string], expand: [], clear: [], cook: [] }>()
const { isDark } = useData()
const CloudKitchen = defineAsyncComponent(() => import('./CloudKitchen.vue'))
function recipeLink(match: RecipeMatch) {
  return match.recipe.bv ? `https://www.bilibili.com/video/${match.recipe.bv}` : match.recipe.link || links.web
}
</script>

<template>
  <section class="cloud-kitchen-hero" aria-labelledby="landing-title">
    <div class="kitchen-intro">
      <div>
        <h1 id="landing-title" class="kitchen-title">
          今晚，开一局厨房。
        </h1><p class="kitchen-lead">
          选几样食材，看看能做什么。
        </p>
      </div>
      <a class="kitchen-web-link" :href="links.web">打开完整版<span aria-hidden="true">↗</span></a>
    </div>
    <div class="kitchen-workbench">
      <div class="kitchen-world">
        <CloudKitchen class="kitchen-scene" :selected="selected" :cooking="cooking" :dark="isDark" @select="emit('select', $event)" @cook="emit('cook')" />
      </div>
      <aside class="recipe-ticket" :class="{ 'is-served': served }" aria-labelledby="ticket-title">
        <div class="ticket-clip" aria-hidden="true" />
        <div class="ticket-heading">
          <h2 id="ticket-title">
            {{ served ? '菜单出炉' : '今天能做' }}
          </h2><span>{{ selected.length }} 样食材</span>
        </div>
        <p class="ticket-note">
          {{ cooking ? '咕嘟咕嘟，菜单准备好了。' : '挑一道，看看做法。' }}
        </p>
        <div class="ticket-results" aria-live="polite">
          <a v-for="match in recipes" :key="match.recipe.id" class="ticket-recipe" :href="recipeLink(match)" target="_blank" rel="noopener noreferrer">
            <span class="ticket-dish" aria-hidden="true">{{ match.recipe.stuff.includes('鸡蛋') ? '🍳' : match.recipe.stuff.includes('牛肉') ? '🍲' : '🍽️' }}</span>
            <span class="ticket-recipe-copy"><strong>{{ match.recipe.name }}</strong><small>{{ match.missingStuff.length ? `还差${match.missingStuff.join('、')}` : '食材齐了，可以开火' }}</small></span>
            <span class="ticket-arrow" aria-hidden="true">↗</span>
          </a>
          <p v-if="!recipes.length" class="ticket-empty">
            厨房还空着，<br>先放几样食材进来吧。
          </p>
        </div>
        <p class="ticket-total">
          找到 {{ matchCount }} 道相关菜谱<br><span>默认备有油盐，包含还差食材的菜谱</span>
        </p>
      </aside>
    </div>
    <div class="kitchen-inventory">
      <LandingIngredients variant="inventory" :selected="selected" :ingredients="ingredients" :expanded="expanded" @select="emit('select', $event)" @expand="emit('expand')" @clear="emit('clear')" />
      <button class="cook-button" type="button" :disabled="!recipes.length || cooking" @click="emit('cook')">
        <svg viewBox="0 0 24 24" width="23" height="23" fill="currentColor" aria-hidden="true"><path d="M13 2c1 5-3 6-2 10 1-2 3-2 4-5 2 3 5 6 5 10a8 8 0 0 1-16 0c0-4 3-7 5-10-1 4 0 5 1 6-1-5 3-7 3-11Z" /></svg>{{ cooking ? '咕嘟咕嘟…' : served ? '再开一锅' : '开火试试' }}
      </button>
    </div>
    <p class="world-hint">
      点点台面上的食材，或点灶台开火
    </p>
  </section>
</template>

<style scoped>
.cloud-kitchen-hero {
  padding: 20px 0 22px;
}
.kitchen-intro {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 20px;
  padding-inline: 8px;
}
.kitchen-title {
  margin: 0;
  padding: 0;
  border: 0;
  color: var(--cook-hero-text);
  font-family: var(--cook-font-display);
  font-size: clamp(36px, 4.2vw, 59px);
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.025em;
}
.kitchen-lead {
  margin: 10px 0 0;
  color: var(--cook-hero-muted);
  font-size: 17px;
  line-height: 1.6;
}
.kitchen-web-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding-bottom: 6px;
  color: var(--cook-hero-muted);
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
}
.kitchen-workbench {
  display: grid;
  grid-template-columns: minmax(0, 760px) 246px;
  justify-content: center;
  gap: 18px;
  align-items: center;
  margin-top: 8px;
}
.kitchen-world {
  position: relative;
  min-width: 0;
  height: 500px;
}
.kitchen-scene {
  height: 100%;
}
.world-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin: 16px 0 0;
  color: var(--cook-hero-muted);
  font-size: 11px;
  pointer-events: none;
}
.recipe-ticket {
  position: relative;
  align-self: start;
  padding: 24px 19px 19px;
  background: var(--cook-ticket-background);
  color: var(--cook-ticket-text);
  box-shadow: 0 9px 28px -18px #34465a48;
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
  transform: rotate(1.5deg);
}
.ticket-clip {
  width: 38px;
  height: 9px;
  margin: -16px auto 18px;
  border-radius: 3px;
  background: #caad7f;
  box-shadow: inset 0 2px #e7d4b2;
}
.ticket-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
}
.ticket-heading h2 {
  margin: 0;
  padding: 0;
  border: 0;
  font-family: var(--cook-font-display);
  font-size: 24px;
  font-weight: 700;
}
.ticket-heading > span {
  color: var(--cook-ticket-muted);
  font-size: 11px;
}
.ticket-note {
  margin: 7px 0 13px;
  color: var(--cook-ticket-muted);
  font-size: 12px;
}
.ticket-results {
  border-block: 1px dashed var(--cook-panel-border);
}
.ticket-recipe {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 16px 0;
  color: inherit;
  text-decoration: none;
}
.ticket-recipe + .ticket-recipe {
  border-top: 1px solid var(--cook-panel-border);
}
.ticket-dish {
  display: grid;
  width: 39px;
  height: 42px;
  flex-shrink: 0;
  place-items: center;
  border-radius: 9px;
  background: var(--cook-ticket-dish);
  font-size: 26px;
}
.ticket-recipe-copy {
  min-width: 0;
}
.ticket-recipe strong {
  display: block;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}
.ticket-recipe small {
  display: block;
  margin-top: 4px;
  color: var(--cook-ticket-muted);
  font-size: 11px;
  line-height: 1.5;
}
.ticket-arrow {
  flex-shrink: 0;
  margin-left: auto;
  color: var(--cook-ticket-muted);
  font-size: 13px;
}
.ticket-empty {
  padding: 32px 0;
  color: var(--cook-ticket-muted);
  font-size: 14px;
  line-height: 1.8;
}
.ticket-total {
  margin: 13px 0 0;
  color: var(--cook-ticket-muted);
  font-size: 11px;
  line-height: 1.8;
}
.ticket-total span {
  font-size: 11px;
}
.kitchen-inventory {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 26px;
  width: fit-content;
  max-width: 100%;
  margin: 14px auto 0;
  padding: 14px 18px 8px;
  border: 1px solid var(--cook-panel-border);
  border-radius: 24px;
  background: var(--cook-panel-background);
  box-shadow:
    0 6px 0 var(--cook-dock-shadow),
    0 16px 34px -24px #34465a33;
}
.cook-button {
  display: flex;
  min-width: 146px;
  min-height: 66px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  gap: 9px;
  align-self: flex-start;
  margin-top: 7px;
  padding: 12px 22px;
  border: 1px solid var(--sky-3);
  border-radius: 16px;
  background: var(--sky-2);
  box-shadow:
    0 4px 0 var(--sky-3),
    inset 0 2px 2px #ffffff55;
  color: var(--cook-action-text);
  font-family: var(--cook-font-display);
  font-size: 18px;
  font-weight: 650;
  cursor: pointer;
}
.cook-button:disabled {
  opacity: 0.6;
  cursor: default;
}
.cook-button:not(:disabled):active {
  transform: translateY(3px);
  box-shadow: 0 1px 0 var(--sky-3);
}
.is-served .ticket-heading h2 {
  color: var(--sky-2);
}
@media (min-width: 960px) {
  .kitchen-scene {
    transform: translateY(-18px);
  }
  .kitchen-inventory {
    margin-top: -92px;
  }
}
@media (max-width: 959px) {
  .kitchen-workbench {
    grid-template-columns: minmax(0, 1fr) 220px;
    gap: 8px;
  }
  .kitchen-world {
    height: 410px;
  }
  .kitchen-inventory {
    gap: 18px;
    padding: 14px 14px 8px;
  }
  .cook-button {
    min-width: 120px;
    padding-inline: 12px;
    font-size: 16px;
  }
}
@media (max-width: 639px) {
  .cloud-kitchen-hero {
    display: flex;
    flex-direction: column;
    padding-top: 20px;
  }
  .kitchen-intro {
    padding: 0;
  }
  .kitchen-title {
    font-size: clamp(29px, 7.3vw, 40px);
  }
  .kitchen-lead {
    margin-top: 9px;
    font-size: 14px;
  }
  .kitchen-web-link {
    display: none;
  }
  .kitchen-workbench {
    display: contents;
  }
  .kitchen-world {
    order: 1;
    width: calc(100% + 24px);
    height: 360px;
    margin-inline: -12px;
  }
  .world-hint {
    order: 2;
    font-size: 11px;
  }
  .recipe-ticket {
    order: 3;
    width: 100%;
    margin-top: 12px;
    transform: none;
  }
  .ticket-recipe {
    padding-block: 12px;
  }
  .kitchen-inventory {
    order: 2;
    flex-direction: column;
    gap: 10px;
    width: 100%;
    margin-top: 20px;
    padding: 14px;
    border-radius: 20px;
  }
  .cook-button {
    width: 100%;
    min-height: 48px;
    margin-top: 0;
    align-self: stretch;
  }
}
@media (prefers-reduced-motion: no-preference) {
  .is-served {
    animation: ticket-reveal 350ms ease-out;
  }
  @keyframes ticket-reveal {
    from {
      transform: translateY(8px) rotate(1.5deg);
    }
    to {
      transform: translateY(0) rotate(1.5deg);
    }
  }
}
</style>
