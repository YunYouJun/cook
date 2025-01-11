<script lang="ts" setup>
import dayjs from 'dayjs'
import { recipeHistories } from '~/composables/store/history'

definePageMeta({
  layout: 'child',
  title: '历史记录',
})

// todo
// clear one history
function clearAllHistory() {
  recipeHistories.value = []
}
</script>

<template>
  <div pt-2>
    <button
      text="blue-900 dark:blue-200"
      bg="blue-300 op-20 hover:(blue-800 op-20) dark:hover:(blue-200 op-20)"
      class="inline-flex items-center justify-center border border-transparent rounded-md px-4 py-2 text-sm font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-blue-500"
      @click="clearAllHistory"
    >
      <span i-ri-eraser-line />
      <span class="ml-1">清空记录</span>
    </button>

    <div flex="~ col">
      <div v-for="history in recipeHistories" :key="history.recipe.name" mt-2>
        <DateTag>
          {{ dayjs(history.time).format('YYYY-MM-DD HH:mm:ss') }}
        </DateTag>
        <DishTag :dish="history.recipe" />
      </div>
    </div>
  </div>
</template>
