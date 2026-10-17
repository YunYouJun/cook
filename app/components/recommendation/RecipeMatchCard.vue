<script setup lang="ts">
import type { RecipeMatch } from '@yunyoujun/cook'
import { shallowRef, useId } from 'vue'

defineProps<{ match: RecipeMatch }>()
const expanded = shallowRef(false)
const detailsId = useId()
</script>

<template>
  <article class="match-card" :aria-label="match.recipe.name">
    <h4 class="match-name">
      <DishTag :dish="match.recipe" :aria-label="`查看${match.recipe.name}的做法（新窗口）`" />
    </h4>
    <dl class="match-status">
      <div class="ingredient-line" :class="match.missingStuff.length ? 'is-missing' : 'is-complete'">
        <dt>缺少</dt><dd>{{ match.missingStuff.length ? match.missingStuff.join('、') : '无，已记录食材齐全' }}</dd>
      </div>
    </dl>
    <button
      type="button"
      class="match-details-toggle"
      :aria-label="`${match.recipe.name}的匹配依据`"
      :aria-expanded="expanded"
      :aria-controls="detailsId"
      @click="expanded = !expanded"
    >
      <span>依据</span>
      <svg class="match-details-chevron" aria-hidden="true" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round">
        <path d="m4 6 4 4 4-4" />
      </svg>
    </button>
    <p class="match-evidence">
      用上 {{ match.matchedStuff.length }} 种
    </p>
    <div :id="detailsId" class="match-details" :class="{ 'is-open': expanded }">
      <div class="match-details-clip">
        <div class="match-details-body">
          <p class="match-tools">
            {{ match.recipe.tools.join(' / ') || '厨具未记录' }}
            <span v-if="match.recipe.difficulty"> · {{ match.recipe.difficulty }}</span>
          </p>
          <p class="match-basis-description">
            用上 {{ match.matchedStuff.length }} 种已选食材，共记录 {{ match.requiredStuff.length }} 种
          </p>
          <dl class="match-ingredients">
            <div class="ingredient-line">
              <dt>已有</dt><dd>{{ match.matchedStuff.join('、') }}</dd>
            </div>
            <div v-if="match.pantryStuff.length" class="ingredient-line">
              <dt>常备</dt><dd>{{ match.pantryStuff.join('、') }}</dd>
            </div>
          </dl>
        </div>
      </div>
    </div>
  </article>
</template>

<style scoped>
.match-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  column-gap: var(--ylf-space-2);
  padding: var(--ylf-space-2) 0;
  border-bottom: 1px solid var(--cook-match-border);
  text-align: left;
}
.match-name {
  grid-column: 1;
  min-width: 0;
  font-size: var(--cook-match-font-title);
  line-height: 1.5;
  font-weight: 600;
}
.match-name :deep(.dish-tag) {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  min-width: var(--cook-match-control-height);
  min-height: var(--cook-match-control-height);
  margin: 0;
  padding: 0;
  border: 0;
  background: none;
  box-shadow: none;
  font-size: inherit;
  line-height: inherit;
}
.match-name :deep(.dish-tag > span:first-child) {
  box-sizing: border-box;
  max-width: 100%;
  margin: 0;
  padding: var(--ylf-space-1) var(--ylf-space-2);
  border: 1px solid var(--cook-match-border);
  border-radius: var(--ylf-radius-pill);
  background: var(--cook-match-soft);
  font-size: inherit;
  color: var(--cook-match-accent);
  overflow-wrap: anywhere;
}
.match-name :deep(.dish-tag > span:first-child)::after {
  content: ' ↗';
  font-size: var(--cook-match-font-secondary);
}
.match-name :deep(.dish-tag > span:not(:first-child)) {
  display: none;
}
.match-status {
  grid-column: 1;
  grid-row: 2;
  min-width: 0;
  font-size: var(--cook-match-font-primary);
  line-height: 1.6;
}
.match-details-toggle {
  grid-column: 2;
  grid-row: 1;
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  min-width: var(--cook-match-control-height);
  min-height: var(--cook-match-control-height);
  gap: var(--ylf-space-1);
  font-size: var(--cook-match-font-primary);
  color: var(--cook-match-muted);
  white-space: nowrap;
}
.match-evidence {
  grid-column: 2;
  grid-row: 2;
  text-align: right;
  white-space: nowrap;
}
.match-evidence,
.match-tools,
.match-basis-description {
  font-size: var(--cook-match-font-secondary);
  color: var(--cook-match-muted);
}
.match-details-chevron {
  width: 12px;
  height: 12px;
  flex: none;
  transition: transform var(--ylf-duration-fast, 160ms) var(--ylf-ease-standard, ease);
}
.match-details-toggle[aria-expanded='true'] .match-details-chevron {
  transform: rotate(180deg);
}
/* 用 grid 行高从 0fr 过渡到 1fr，展开收起都有高度动画；收起后 visibility 让内容退出焦点与读屏 */
.match-details {
  grid-column: 1 / -1;
  display: grid;
  grid-template-rows: 0fr;
  visibility: hidden;
  transition:
    grid-template-rows 0.26s cubic-bezier(0.2, 0.8, 0.2, 1),
    visibility 0s linear 0.26s;
}
.match-details.is-open {
  grid-template-rows: 1fr;
  visibility: visible;
  transition-delay: 0s;
}
.match-details-clip {
  min-height: 0;
  overflow: hidden;
}
.match-details-body {
  margin-top: var(--ylf-space-1);
  padding: var(--ylf-space-2) var(--ylf-space-3);
  border-radius: var(--ylf-radius-sm);
  background: var(--cook-match-surface);
}
.match-basis-description {
  margin-top: var(--ylf-space-1);
}
.match-ingredients {
  margin-top: var(--ylf-space-1);
  font-size: var(--cook-match-font-primary);
  line-height: 1.6;
}
.ingredient-line {
  display: flex;
  gap: var(--ylf-space-2);
  margin: var(--ylf-space-1) 0;
}
.ingredient-line dt {
  flex: 0 0 2em;
  color: var(--cook-match-muted);
}
.ingredient-line dd {
  min-width: 0;
  overflow-wrap: anywhere;
}
.is-missing dd {
  color: var(--cook-match-missing);
  font-weight: 600;
}
.is-complete dd {
  color: var(--cook-match-accent);
}
@media (prefers-reduced-motion: reduce) {
  .match-details-chevron,
  .match-details {
    transition: none;
  }
}
</style>
