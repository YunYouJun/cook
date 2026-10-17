<script setup lang="ts">
import type { RecipeMatch } from '@yunyoujun/cook'
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import { useStaggerEnter } from '~/composables/animation'

const props = defineProps<{ matches: RecipeMatch[] }>()
const labels = ['食材齐全', '还差一种', '还差两种']
const active = shallowRef(0)
const limit = shallowRef(6)
const groups = computed(() => labels.map((label, count) => ({ label, items: props.matches.filter(item => item.missingStuff.length === count) })))
const current = computed(() => groups.value[active.value]!)
const visible = computed(() => current.value.items.slice(0, limit.value))
const onBeforeEnter = useStaggerEnter()

watch(() => props.matches, () => {
  active.value = Math.max(0, groups.value.findIndex(group => group.items.length))
  limit.value = 6
}, { immediate: true })

function select(index: number) {
  active.value = index
  limit.value = 6
}
</script>

<template>
  <div>
    <div class="match-groups" role="group" aria-label="按缺少食材筛选" :style="{ '--active': active }">
      <button v-for="group, index in groups" :key="group.label" class="match-group" :aria-pressed="active === index" aria-controls="match-results" @click="select(index)">
        <span>{{ group.label }}</span>
        <strong>{{ group.items.length }}<span class="group-unit"> 道</span></strong>
      </button>
      <span class="match-group-indicator" aria-hidden="true" />
    </div>
    <section id="match-results" :aria-label="current.label">
      <h3 class="results-heading" role="status">
        {{ current.label }} <span class="results-count">· {{ current.items.length }} 道</span>
      </h3>
      <p v-if="!current.items.length" class="group-empty">
        这个分组暂时没有菜谱，试试其他分组或调整食材。
      </p>
      <TransitionGroup tag="div" name="recipe-list" appear @before-enter="onBeforeEnter">
        <RecipeMatchCard v-for="match in visible" :key="match.sourceIndex" :match="match" />
      </TransitionGroup>
      <YlfButton v-if="current.items.length > limit" type="button" variant="secondary" :round="false" block class="show-more" @click="limit += 6">
        再看 6 道（还有 {{ current.items.length - limit }} 道）
      </YlfButton>
    </section>
  </div>
</template>

<style scoped>
.match-groups {
  position: relative;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--ylf-space-1);
  border-bottom: 1px solid var(--cook-match-border);
}
.match-group {
  min-height: 60px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--ylf-space-1);
  border-bottom: 3px solid transparent;
  font-size: var(--cook-match-font-primary);
  color: var(--cook-match-muted);
  transition:
    color var(--ylf-duration-fast, 160ms) ease,
    background-color var(--ylf-duration-fast, 160ms) ease;
}
/* 三个分组共用一条指示线，切换时滑过去 */
.match-group-indicator {
  position: absolute;
  left: 0;
  bottom: 0;
  width: calc((100% - 2 * var(--ylf-space-1)) / 3);
  height: 3px;
  background: var(--cook-match-accent);
  transform: translateX(calc(var(--active) * (100% + var(--ylf-space-1))));
  transition: transform 0.32s cubic-bezier(0.2, 0.8, 0.2, 1);
  pointer-events: none;
}
.match-group strong {
  font-size: var(--cook-match-font-secondary);
  font-weight: 500;
}
.results-count,
.group-unit {
  font-size: var(--cook-match-font-secondary);
  font-weight: 400;
}
.match-group[aria-pressed='true'] {
  color: var(--cook-match-accent);
  background: var(--cook-match-soft);
}
.results-heading {
  font-size: var(--cook-match-font-primary);
  color: var(--cook-match-muted);
  margin-top: var(--ylf-space-3);
}
.group-empty {
  font-size: var(--cook-match-font-primary);
  padding: var(--ylf-space-6) 0;
  color: var(--cook-match-muted);
}
.show-more {
  min-height: var(--ylf-space-12);
  width: 100%;
  color: var(--cook-match-accent);
  margin-top: var(--ylf-space-2);
  border: 1px solid var(--cook-match-border);
  border-radius: var(--ylf-radius-sm);
}
@media (prefers-reduced-motion: reduce) {
  .match-group,
  .match-group-indicator {
    transition: none;
  }
}
</style>
