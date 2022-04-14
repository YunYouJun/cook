<script lang="ts" setup>
import { useGtm } from '@gtm-support/vue-gtm'
import type { RecipeItem } from '~/types'
defineProps<{
  dish: RecipeItem
}>()

const gtm = useGtm()

const triggerGtm = (val: string) => {
  gtm?.trackEvent({
    event: 'recipe',
    category: 'dish',
    action: 'click',
    label: '跳转菜谱',
    value: val,
  })
}
</script>

<template>
  <a
    :href="dish.link" target="_blank" class="tag rounded" p="x-2"
    border="~ blue-200 dark:blue-800"
    bg="blue-300 opacity-20"
    @click="triggerGtm(dish.name)"
  >
    <span text="sm blue-700 dark:blue-200">{{ dish.name }}</span>
  </a>
</template>
