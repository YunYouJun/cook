<script lang="ts" setup>
import type { RecipeItem } from '~/types'
defineProps<{
  dish: RecipeItem
}>()
</script>

<template>
  <a
    :href="dish.link" target="_blank" class="tag rounded" p="x-2"
    border="~ blue-200 dark:blue-800"
    bg="blue-300 opacity-20"
  >
    <span text="sm blue-700 dark:blue-200">{{ dish.name }}</span>
  </a>
</template>
