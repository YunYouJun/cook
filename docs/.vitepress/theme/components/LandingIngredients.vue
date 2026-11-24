<script setup lang="ts">
import type { StuffItem } from '../landing'
import { computed } from 'vue'
import { ingredientImage } from '../cloud-kitchen/ingredient-catalog'

const props = withDefaults(defineProps<{
  selected: readonly string[]
  ingredients: readonly StuffItem[]
  expanded: boolean
  variant?: 'pantry' | 'inventory'
}>(), { variant: 'pantry' })

const emit = defineEmits<{ select: [name: string], expand: [], clear: [] }>()
const pantryNames = ['番茄', '鸡蛋', '土豆', '黄瓜']

const shown = computed(() => {
  if (props.variant === 'inventory' || props.expanded)
    return props.ingredients
  const rank = (name: string) => pantryNames.includes(name) ? pantryNames.indexOf(name) : pantryNames.length
  return props.ingredients.filter(item => pantryNames.includes(item.name) || props.selected.includes(item.name))
    .sort((a, b) => rank(a.name) - rank(b.name))
})
</script>

<template>
  <div class="ingredient-picker" :class="variant">
    <div id="hero-ingredients" class="ingredient-items" role="group" aria-label="选食材，看看能做什么">
      <button
        v-for="item in shown"
        :key="item.name"
        class="ingredient-item"
        type="button"
        :aria-pressed="selected.includes(item.name)"
        @click="emit('select', item.name)"
      >
        <span class="ingredient-art" aria-hidden="true">
          <img v-if="ingredientImage(item.name)" :src="ingredientImage(item.name)" :alt="item.name" width="52" height="52">
          <template v-else>{{ item.emoji }}</template>
        </span>
        <span class="ingredient-name">{{ item.name }}</span>
        <span v-if="selected.includes(item.name)" class="ingredient-check" aria-hidden="true">
          <svg class="ingredient-check-icon" viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="m3.5 8 3 3 6-6" /></svg>
        </span>
      </button>
    </div>
    <div class="ingredient-tools">
      <button type="button" :aria-expanded="expanded" aria-controls="hero-ingredients" @click="emit('expand')">
        {{ expanded ? '收起更多食材' : '展开全部常用食材' }}
      </button>
      <button v-if="selected.length" type="button" @click="emit('clear')">
        清空已选
      </button>
    </div>
  </div>
</template>

<style scoped>
.ingredient-items {
  display: flex;
  flex-wrap: wrap;
  gap: 9px;
}

.ingredient-item {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 42px;
  padding: 7px 13px;
  border: 1px solid var(--cook-panel-border);
  border-radius: 14px;
  background: var(--cook-chip-background);
  color: var(--cook-hero-muted);
  font-family: var(--cook-font-body);
  font-size: 13px;
  cursor: pointer;
  transition:
    transform 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    border-color 0.2s ease,
    background-color 0.2s ease,
    box-shadow 0.2s ease,
    color 0.2s ease;
}

.ingredient-item:hover {
  transform: translateY(-3px);
  background: var(--cook-chip-hover);
  color: var(--cook-hero-text);
}

.ingredient-item:active {
  transform: translateY(1px) scale(0.97);
}

.ingredient-art {
  font-family: system-ui, sans-serif;
  font-size: 22px;
  line-height: 1.2;
  transition: transform 0.24s cubic-bezier(0.22, 1, 0.36, 1);
}

.ingredient-item:hover .ingredient-art {
  transform: scale(1.1) rotate(-4deg);
}

.ingredient-art img {
  width: 32px;
  height: 32px;
  object-fit: contain;
}

.inventory .ingredient-art img {
  width: 52px;
  height: 44px;
  object-fit: cover;
}

.ingredient-item[aria-pressed='true'] {
  border-color: var(--cook-chip-selected-border);
  background: var(--cook-chip-selected);
  color: var(--cook-chip-selected-text);
  box-shadow: 0 3px 0 var(--cook-chip-selected-border);
}

.ingredient-check {
  display: none;
}

.ingredient-tools {
  display: flex;
  gap: 18px;
  margin-top: 9px;
  color: var(--cook-hero-muted);
  font-size: 12px;
}

.ingredient-tools button {
  min-height: 28px;
  cursor: pointer;
  transition: color 0.2s ease;
}

.ingredient-tools button:hover {
  color: var(--sky-2);
}

.inventory .ingredient-items {
  gap: 8px;
}

.inventory .ingredient-item {
  flex-direction: column;
  gap: 5px;
  width: 72px;
  min-height: 82px;
  padding: 9px 6px 7px;
  border-radius: 16px;
  box-shadow: 0 3px 0 var(--cook-panel-border);
}

.inventory .ingredient-item[aria-pressed='true'] {
  box-shadow: 0 3px 0 var(--cook-chip-selected-border);
}

.inventory .ingredient-art {
  font-size: 33px;
}

.inventory .ingredient-name {
  font-size: 12px;
  font-weight: 600;
}

.inventory .ingredient-check {
  position: absolute;
  top: -5px;
  right: -4px;
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  border-radius: 50%;
  background: var(--cook-check-bg);
  color: var(--cook-check-text);
  box-shadow: 0 2px 6px rgba(180, 83, 9, 0.35);
  line-height: 1;
  animation: check-pop 0.26s cubic-bezier(0.3, 1.6, 0.5, 1);
}

@keyframes check-pop {
  from {
    transform: scale(0.4);
    opacity: 0;
  }
}

.ingredient-check-icon {
  display: block;
}

.inventory .ingredient-tools {
  margin-top: 6px;
}

@media (max-width: 639px) {
  .pantry .ingredient-items {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 7px;
  }
  .pantry .ingredient-item {
    gap: 4px;
    padding-inline: 6px;
    font-size: 12px;
  }
  .pantry .ingredient-art {
    font-size: 20px;
  }
  .inventory .ingredient-item {
    width: 100%;
    min-height: 72px;
    padding-inline: 3px;
  }
  .inventory .ingredient-art {
    font-size: 28px;
  }
  .inventory .ingredient-items {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 10px 8px;
  }
  .inventory {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ingredient-item,
  .ingredient-art,
  .ingredient-tools button {
    transition: none;
  }
  .ingredient-item:hover,
  .ingredient-item:hover .ingredient-art {
    transform: none;
  }
  .ingredient-check {
    animation: none;
  }
}
</style>
