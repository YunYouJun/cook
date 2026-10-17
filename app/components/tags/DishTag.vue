<script lang="ts" setup>
import type { RecipeItem } from '~/types'
import type { DbRecipeItem } from '~/utils/db'
import { addRecipeHistory } from '~/composables/store/history'
import { tools } from '~/data/food'
import { getEmojisFromStuff } from '~/utils'

const props = defineProps<{
  dish: RecipeItem | DbRecipeItem
}>()

const { proxy } = useScriptGoogleTagManager()

function triggerGtm(dish: RecipeItem) {
  addRecipeHistory(dish)

  proxy.dataLayer.push({
    event: 'click',
    category: `dish_${dish.name}`,
    action: 'click_recipe',
    label: '跳转菜谱',
  })
  proxy.dataLayer.push({
    event: 'click_dish',
    action: dish.name,
  })
}

const dishLabel = computed(() => {
  const emojis = getEmojisFromStuff(props.dish.stuff)
  return `${props.dish.tags?.includes('杂烩') ? '🍲' : emojis.join(' ')} ${props.dish.name}`
})
</script>

<template>
  <a
    :href="dish.link || `https://www.bilibili.com/video/${dish.bv}`" target="_blank" class="dish-tag tag rounded" p="x-2"
    border="~ blue-200 dark:blue-800"
    bg="blue-300 opacity-20"
    @click="triggerGtm(dish)"
  >
    <span m="r-1" text="sm blue-700 dark:blue-200">
      <slot>{{ dishLabel }}</slot>
    </span>
    <template v-for="tool, i in tools">
      <span v-if="dish.tools?.includes(tool.name)" :key="i" :class="tool.icon" />
    </template>
  </a>
</template>
