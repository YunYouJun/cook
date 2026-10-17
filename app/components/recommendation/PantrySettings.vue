<script setup lang="ts">
import YlfBadge from '@yunlefun/vue/components/YlfBadge.vue'
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import { pantryOptions } from '~/data/pantry'

defineProps<{ selected: string[] }>()
const emit = defineEmits<{ toggle: [name: string], clear: [], reset: [] }>()
</script>

<template>
  <details class="pantry-settings">
    <summary class="pantry-summary">
      <span>🧂 常备调料 <YlfBadge class="pantry-count" variant="neutral">{{ selected.length }} 种</YlfBadge></span>
      <span class="pantry-edit">调整</span>
    </summary>
    <p class="pantry-description">
      勾选家里已有的调料。仅在菜谱明确列出时计入匹配，不会额外增加必需材料。
    </p>
    <div class="pantry-options" role="group" aria-label="常备调料">
      <YlfButton v-for="name in pantryOptions" :key="name" type="button" :variant="selected.includes(name) ? 'soft' : 'secondary'" :round="false" class="pantry-option" :aria-pressed="selected.includes(name)" @click="emit('toggle', name)">
        <span aria-hidden="true">{{ selected.includes(name) ? '✓' : '+' }}</span> {{ name }}
      </YlfButton>
    </div>
    <div class="pantry-actions">
      <YlfButton type="button" variant="ghost" :round="false" @click="emit('clear')">
        全部取消
      </YlfButton>
      <YlfButton type="button" variant="ghost" :round="false" @click="emit('reset')">
        恢复油、盐
      </YlfButton>
    </div>
  </details>
</template>

<style scoped>
.pantry-settings {
  margin: var(--ylf-space-3) var(--ylf-space-3);
  padding: var(--ylf-space-1) var(--ylf-space-3);
  text-align: left;
  border-block: 1px solid var(--cook-match-border);
}
.pantry-summary {
  display: flex;
  justify-content: space-between;
  align-items: center;
  cursor: pointer;
  min-height: var(--cook-match-control-height);
  font-weight: 600;
  list-style: none;
}
.pantry-summary::-webkit-details-marker {
  display: none;
}
.pantry-count {
  color: var(--cook-match-muted);
  font-size: var(--cook-match-font-secondary);
  font-weight: 400;
  margin-left: var(--ylf-space-2);
}
.pantry-edit {
  color: var(--cook-match-accent);
  font-size: var(--cook-match-font-primary);
}
.pantry-edit::after {
  content: ' ＋';
}
.pantry-settings[open] .pantry-edit::after {
  content: ' −';
}
.pantry-description {
  color: var(--cook-match-muted);
  font-size: var(--cook-match-font-secondary);
  line-height: 1.6;
  margin: var(--ylf-space-1) 0 var(--ylf-space-3);
}
.pantry-options {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ylf-space-2);
}
.pantry-option {
  min-height: var(--cook-match-control-height);
  padding: var(--ylf-space-2) var(--ylf-space-3);
  border-radius: var(--ylf-radius-sm);
}
.pantry-option[aria-pressed='true'] {
  border-color: var(--cook-match-accent);
  color: var(--cook-match-accent);
  background: var(--cook-match-soft);
}
.pantry-actions {
  display: flex;
  gap: var(--ylf-space-6);
  color: var(--cook-match-accent);
  font-size: var(--cook-match-font-primary);
  margin: var(--ylf-space-2) 0;
}
.pantry-actions button {
  min-height: var(--cook-match-control-height);
}
</style>
