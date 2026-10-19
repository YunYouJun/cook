<script lang="ts" setup>
import type { RecipeItem } from '~/types'
import type { DbRecipeItem } from '~/utils/db'
import { addRecipeHistory } from '~/composables/store/history'
import { tools } from '~/data/food'
import { getEmojisFromStuff } from '~/utils'
import { trackCookEvent } from '~/utils/analytics'
import { getRecipeLink } from '~/utils/recipe'

const props = defineProps<{
  dish: RecipeItem | DbRecipeItem
}>()

function onRecipeClick(dish: RecipeItem) {
  addRecipeHistory(dish)
  trackCookEvent('click_dish')
  trackCookEvent('click_recipe_video')
}

const dishLabel = computed(() => {
  const emojis = getEmojisFromStuff(props.dish.stuff)
  return `${props.dish.tags?.includes('杂烩') ? '🍲' : emojis.join(' ')} ${props.dish.name}`
})
</script>

<template>
  <a
    :href="getRecipeLink(dish)" target="_blank" class="dish-tag tag rounded" p="x-2"
    border="~ blue-200 dark:blue-800"
    bg="blue-300 opacity-20"
    @click="onRecipeClick(dish)"
  >
    <span m="r-1" text="sm blue-700 dark:blue-200">
      <slot>{{ dishLabel }}</slot>
    </span>
    <template v-for="tool, i in tools">
      <span v-if="dish.tools?.includes(tool.name)" :key="i" :class="tool.icon" />
    </template>
  </a>
</template>
