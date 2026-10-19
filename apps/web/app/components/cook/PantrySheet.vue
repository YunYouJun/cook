<script lang="ts" setup>
import { pantryOptions } from '~/data/pantry'

/**
 * 常备调料：底部弹层里勾选，只在菜谱明确列出时计入匹配
 */
defineProps<{ selected: string[] }>()
const emit = defineEmits<{ toggle: [name: string], clear: [], reset: [] }>()
const open = defineModel<boolean>('open', { required: true })
</script>

<template>
  <ion-modal :is-open="open" :breakpoints="[0, 0.5]" :initial-breakpoint="0.5" @did-dismiss="open = false">
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-button @click="emit('reset')">
            恢复默认
          </ion-button>
        </ion-buttons>
        <ion-title>常备调料</ion-title>
        <ion-buttons slot="end">
          <ion-button :strong="true" @click="open = false">
            完成
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <div class="pantry-chips" role="group" aria-label="常备调料">
        <IngredientChip
          v-for="name in pantryOptions"
          :key="name"
          :label="name"
          :selected="selected.includes(name)"
          @click="emit('toggle', name)"
        />
      </div>
      <p class="cook-group-footer pantry-note">
        只在菜谱明确列出时算作已有，不会额外增加必需的材料。
        <button v-if="selected.length" type="button" class="pantry-clear" @click="emit('clear')">
          全部取消
        </button>
      </p>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.pantry-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px;
  padding: 12px;
  border-radius: 10px;
  background: var(--ion-item-background, #fff);
}
.pantry-note {
  margin-top: 0;
}
.pantry-clear {
  margin-inline-start: 4px;
  color: var(--ion-color-primary);
}
</style>
