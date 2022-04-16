<script lang="ts" setup>
import { useGtm } from '@gtm-support/vue-gtm'
import type { RecipeItem } from '~/types'
defineProps<{
  dish: RecipeItem
}>()

const gtm = useGtm()

const triggerGtm = (val: string) => {
  gtm?.trackEvent({
    event: 'click',
    category: `dish_${val}`,
    action: 'click_recipe',
    label: '跳转菜谱',
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
    <span text="sm blue-700 dark:blue-200">{{ dish.emojis.join(' ') + ' ' + dish.name }}</span>
  </a>
</template>
