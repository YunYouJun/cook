<script setup lang="ts">
import YlfBadge from '@yunlefun/vue/components/YlfBadge.vue'
import YlfButton from '@yunlefun/vue/components/YlfButton.vue'
import YlfCard from '@yunlefun/vue/components/YlfCard.vue'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { meat, staple, vegetable } from '~/data/food'

const props = defineProps<{ selected: string[] }>()
const emit = defineEmits<{ add: [name: string], remove: [name: string], clear: [] }>()
const input = shallowRef('')
const message = shallowRef('')
const suggestions = [...vegetable, ...meat, ...staple].map(item => item.name)

function add() {
  const names = uniqueStuff([input.value])
  if (!names.length) {
    message.value = '请输入食材名称。'
    return
  }
  const newNames = names.filter(name => !props.selected.includes(name))
  newNames.forEach(name => emit('add', name))
  message.value = newNames.length ? `已加入 ${newNames.join('、')}` : '这些食材已经在篮子里了，不用重复添加。'
  input.value = ''
}
</script>

<template>
  <YlfCard id="ingredient-basket" class="ingredient-basket" role="region" aria-label="我的食材篮" :hoverable="false" padding="var(--ylf-space-3)">
    <div class="basket-heading">
      <h2 class="basket-title">
        🧺 我的食材 <YlfBadge class="basket-count" variant="neutral">
          {{ selected.length }} 种
        </YlfBadge>
      </h2>
      <YlfButton v-if="selected.length" type="button" variant="ghost" :round="false" class="quiet-button" @click="emit('clear')">
        清空食材
      </YlfButton>
    </div>
    <p v-if="!selected.length" class="basket-hint">
      点选下面的食材，或输入冰箱里已有的食材。
    </p>
    <div v-else class="basket-items">
      <YlfButton v-for="name in selected" :key="name" type="button" variant="accent" appearance="outline" :round="false" class="basket-item" :aria-label="`移除${name}`" @click="emit('remove', name)">
        <span class="basket-item-label"><span>{{ name }}</span><span aria-hidden="true">×</span></span>
      </YlfButton>
    </div>
    <form class="basket-form" @submit.prevent="add">
      <input v-model="input" class="basket-input" aria-label="补充食材" placeholder="补充食材，如西红柿、鸡蛋" list="ingredient-suggestions" autocomplete="off">
      <datalist id="ingredient-suggestions">
        <option v-for="name in suggestions" :key="name" :value="name" />
      </datalist>
      <YlfButton class="add-button" type="submit" variant="primary" :round="false">
        加入
      </YlfButton>
    </form>
    <p class="basket-message" role="status">
      {{ message }}
    </p>
  </YlfCard>
</template>

<style scoped>
.ingredient-basket {
  margin: var(--ylf-space-3) var(--ylf-space-3) var(--ylf-space-2);
  text-align: left;
}
.basket-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ylf-space-2);
}
.basket-title {
  font-size: var(--ylf-text-lg);
  font-weight: 700;
}
.basket-count {
  color: var(--cook-match-muted);
  font-size: var(--cook-match-font-secondary);
  font-weight: 400;
  margin-left: var(--ylf-space-2);
}
.quiet-button {
  min-height: var(--cook-match-control-height);
  color: var(--cook-match-accent);
  font-size: var(--cook-match-font-primary);
}
.basket-hint {
  color: var(--cook-match-muted);
  font-size: var(--cook-match-font-secondary);
  margin: var(--ylf-space-1) 0 var(--ylf-space-3);
}
.basket-items {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ylf-space-2);
  margin: var(--ylf-space-1) 0 var(--ylf-space-2);
}
.basket-item {
  display: flex;
  max-width: 100%;
  gap: var(--ylf-space-3);
  align-items: center;
  padding: var(--ylf-space-2) var(--ylf-space-3);
  min-height: var(--cook-match-control-height);
  border-radius: var(--ylf-radius-sm);
  color: var(--cook-match-accent);
  overflow-wrap: anywhere;
}
.basket-item-label {
  display: inline-flex;
  align-items: center;
  gap: var(--ylf-space-3);
}
.basket-form {
  display: flex;
  gap: var(--ylf-space-2);
}
.basket-input {
  min-width: 0;
  flex: 1;
  padding: var(--ylf-space-2);
  background: var(--cook-match-input);
  border: 1px solid var(--cook-match-border);
  border-radius: var(--ylf-radius-sm);
  font-size: var(--cook-match-font-input);
}
.add-button {
  min-height: var(--cook-match-control-height);
  border-radius: var(--ylf-radius-sm);
}
.basket-message {
  overflow-wrap: anywhere;
  font-size: var(--cook-match-font-secondary);
  color: var(--cook-match-muted);
}
.basket-message:not(:empty) {
  margin-top: var(--ylf-space-2);
}
</style>
