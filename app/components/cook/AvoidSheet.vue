<script lang="ts" setup>
import { meat, staple, vegetable } from '~/data/food'

/**
 * 忌口：底部弹层里勾选，用到这些食材的菜不再推荐（大杂烩只是不再建议加）
 */
defineProps<{ selected: string[] }>()
const emit = defineEmits<{ toggle: [name: string], clear: [] }>()
const open = defineModel<boolean>('open', { required: true })

const sections = [
  { title: '蔬菜', items: vegetable },
  { title: '肉和蛋', items: meat },
  { title: '主食', items: staple },
]
</script>

<template>
  <ion-modal :is-open="open" :breakpoints="[0, 0.75]" :initial-breakpoint="0.75" @did-dismiss="open = false">
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-button :disabled="!selected.length" @click="emit('clear')">
            全部取消
          </ion-button>
        </ion-buttons>
        <ion-title>忌口</ion-title>
        <ion-buttons slot="end">
          <ion-button :strong="true" @click="open = false">
            完成
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <template v-for="section in sections" :key="section.title">
        <h2 class="cook-group-header">
          {{ section.title }}
        </h2>
        <div class="avoid-chips" role="group" :aria-label="`忌口：${section.title}`">
          <IngredientChip
            v-for="item in section.items"
            :key="item.name"
            :label="item.name"
            :emoji="item.emoji"
            :selected="selected.includes(item.name)"
            @click="emit('toggle', item.name)"
          />
        </div>
      </template>
      <p class="cook-group-footer">
        用到这些食材的菜不再推荐，「今天吃什么」也会避开。火锅这类大杂烩照常推荐，只是不再建议往里加。
      </p>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.avoid-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 16px;
  padding: 12px;
  border-radius: 10px;
  background: var(--ion-item-background, #fff);
}
</style>
