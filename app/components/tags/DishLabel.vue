<script lang="ts" setup>
import type { RecipeItem } from '~/types'
import type { DbRecipeItem } from '~/utils/db'
import { tools } from '~/data/food'
import { getEmojisFromStuff } from '~/utils'

const props = defineProps<{
  dish: RecipeItem | DbRecipeItem
}>()

const dishLabel = computed(() => {
  const emojis = getEmojisFromStuff(props.dish.stuff)
  return `${props.dish.tags?.includes('杂烩') ? '🍲' : emojis.join(' ')} ${props.dish.name}`
})
</script>

<template>
  <span>
    {{ dishLabel }}

    <template v-for="tool, i in tools">
      <span v-if="dish.tools?.includes(tool.name)" :key="i" :class="tool.icon" />
    </template>
  </span>
</template>
