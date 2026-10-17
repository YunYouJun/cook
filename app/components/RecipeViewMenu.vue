<script setup lang="ts">
import type { RecipeView } from '~/utils/settings'
import { resolveRecipeView } from '~/utils/settings'

const model = defineModel<RecipeView>({ required: true })
const menuOptions = {
  cssClass: 'recipe-view-popover',
  header: '做菜视图',
  message: '匹配视图可查看缺料，选择会自动记住。',
}
</script>

<template>
  <ion-select
    class="recipe-view-menu"
    aria-label="做菜视图"
    interface="popover"
    :interface-options="menuOptions"
    :value="model"
    @ion-change="model = resolveRecipeView($event.detail.value)"
  >
    <ion-select-option value="classic">
      经典视图
    </ion-select-option>
    <ion-select-option value="recommendation">
      匹配视图
    </ion-select-option>
  </ion-select>
</template>

<style scoped>
.recipe-view-menu {
  min-width: 44px;
  min-height: 44px;
  padding-inline: 8px;
  font-size: 14px;
  color: var(--ion-color-primary);
  border-radius: 8px;
}
.recipe-view-menu:active {
  background: var(--ion-color-primary-tint);
  color: var(--ion-color-primary-contrast);
}
.recipe-view-menu.ion-focused {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: -2px;
}
:global(.recipe-view-popover ion-item p) {
  color: var(--c-text);
}
</style>
