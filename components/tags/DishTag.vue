<script lang="ts" setup>
import type { DbRecipeItem } from 'utils/db'
import { tools } from '~/data/food'
import type { RecipeItem } from '~/types'
import { getEmojisFromStuff } from '~/utils'

const props = defineProps<{
  dish: RecipeItem | DbRecipeItem
}>()

const gtm = useGtm()

function triggerGtm(val: string) {
  gtm?.trackEvent({
    event: 'click',
    category: `dish_${val}`,
    action: 'click_recipe',
    label: '跳转菜谱',
  })
  gtm?.trackEvent({
    event: 'click_dish',
    action: val,
  })
}

const dishLabel = computed(() => {
  const emojis = getEmojisFromStuff(props.dish.stuff)
  return `${props.dish.tags?.includes('杂烩') ? '🍲' : emojis.join(' ')} ${props.dish.name}`
})
</script>

<template>
  <a
    :href="dish.link || `https://www.bilibili.com/video/${dish.bv}`" target="_blank" class="dish-tag rounded tag" p="x-2"
    border="~ blue-200 dark:blue-800"
    bg="blue-300 opacity-20"
    @click="triggerGtm(dish.name)"
  >
    <span m="r-1" class="inline-flex items-center justify-center" text="sm blue-700 dark:blue-200">
      {{ dishLabel }}
    </span>
    <span v-for="tool, i in tools" :key="i" inline-flex>
      <div v-if="dish.tools?.includes(tool.name)" :class="tool.icon" />
    </span>
  </a>
</template>
