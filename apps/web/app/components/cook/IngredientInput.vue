<script lang="ts" setup>
import type { IonSearchbarCustomEvent, SearchbarInputEventDetail } from '@ionic/core'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { meat, staple, vegetable } from '~/data/food'
import { yunleHaptic } from '~/utils/yunleHost'

/**
 * 输入框添加食材 + 已选食材标签（可横向滚动，点一下移除）
 */
const props = defineProps<{ selected: string[] }>()
const emit = defineEmits<{ add: [name: string], remove: [name: string] }>()

const query = ref('')
const message = ref('')
const foods = [...vegetable, ...meat, ...staple]

const suggestions = computed(() => {
  const text = query.value.trim()
  if (!text)
    return []
  return foods
    .filter(item => !props.selected.includes(item.name) && (item.name.includes(text) || item.alias?.includes(text)))
    .slice(0, 6)
})

function onInput(ev: IonSearchbarCustomEvent<SearchbarInputEventDetail>) {
  query.value = ev.detail.value ?? ''
  message.value = ''
}

function add(text = query.value) {
  const names = uniqueStuff([text])
  if (!names.length)
    return
  const fresh = names.filter(name => !props.selected.includes(name))
  fresh.forEach(name => emit('add', name))
  message.value = fresh.length ? '' : '已经选过了'
  if (fresh.length)
    void yunleHaptic('light')
  query.value = ''
}

function remove(name: string) {
  emit('remove', name)
  void yunleHaptic('light')
}
</script>

<template>
  <div class="ingredient-input">
    <ion-searchbar
      :value="query"
      class="ingredient-input-field"
      placeholder="添加食材，如西红柿、鸡蛋"
      aria-label="添加食材"
      enterkeyhint="done"
      :search-icon="ioniconsAddCircleOutline"
      show-clear-button="focus"
      @ion-input="onInput"
      @keydown.enter.prevent="add()"
    />
    <div v-if="suggestions.length" class="ingredient-input-row" role="listbox" aria-label="食材建议">
      <button v-for="item in suggestions" :key="item.name" type="button" class="ingredient-suggestion" role="option" @click="add(item.name)">
        <span aria-hidden="true">{{ item.emoji }}</span> {{ item.name }}
      </button>
    </div>
    <p v-else-if="message" class="ingredient-input-message" role="status">
      {{ message }}
    </p>
    <TransitionGroup v-if="selected.length" tag="div" name="ingredient-token" class="ingredient-input-row" aria-label="已选食材">
      <button v-for="name in selected" :key="name" type="button" class="ingredient-token" :aria-label="`移除${name}`" @click="remove(name)">
        {{ name }}<ion-icon :icon="ioniconsClose" aria-hidden="true" />
      </button>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.ingredient-input-field {
  padding-inline: 12px;
  padding-bottom: 0;
}
.ingredient-input-row {
  position: relative;
  display: flex;
  gap: 8px;
  padding: 10px 16px 2px;
  overflow-x: auto;
  scrollbar-width: none;
}
.ingredient-input-row::-webkit-scrollbar {
  display: none;
}
.ingredient-token,
.ingredient-suggestion {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 4px;
  height: 30px;
  padding: 0 10px 0 12px;
  border-radius: 15px;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
}
.ingredient-token {
  background: rgba(var(--ion-color-primary-rgb), 0.14);
  color: var(--ion-color-primary);
}
.ingredient-token ion-icon {
  font-size: 15px;
  opacity: 0.7;
}
.ingredient-suggestion {
  padding-inline: 12px;
  background: var(--cook-fill);
  color: var(--ion-text-color, #000);
  font-weight: 400;
}
.ingredient-token:active,
.ingredient-suggestion:active {
  opacity: 0.6;
}
.ingredient-input-message {
  margin: 0;
  padding: 8px 32px 0;
  font-size: 13px;
  color: var(--ion-color-medium);
}
.ingredient-token-enter-active,
.ingredient-token-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.24s cubic-bezier(0.2, 0.8, 0.2, 1);
}
.ingredient-token-enter-from,
.ingredient-token-leave-to {
  opacity: 0;
  transform: scale(0.8);
}
.ingredient-token-leave-active {
  position: absolute;
}
.ingredient-token-move {
  transition: transform 0.24s cubic-bezier(0.2, 0.8, 0.2, 1);
}
@media (prefers-reduced-motion: reduce) {
  .ingredient-token-enter-active,
  .ingredient-token-leave-active,
  .ingredient-token-move {
    transition: opacity 0.15s ease;
  }
  .ingredient-token-enter-from,
  .ingredient-token-leave-to {
    transform: none;
  }
}
</style>
