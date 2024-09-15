<script lang="ts" setup>
import { storeToRefs } from 'pinia'

const props = defineProps({
  isVisible: Boolean,
})

const rStore = useRecipeStore()
const { displayedRecipe } = storeToRefs(rStore)

/**
 * Show basket button if there are recipes in the basket
 */
const showBasketBtn = computed(() => {
  return displayedRecipe.value.length !== rStore.recipesLength && props.isVisible
})
</script>

<template>
  <button
    v-show="showBasketBtn"
    class="fixed z-9 inline-flex cursor-pointer items-center justify-center rounded rounded-full shadow hover:shadow-md"
    bg="green-50 dark:green-900" w="10" h="10"
    bottom="22"
    right="4"
    text="green-600 dark:green-300"
  >
    <span v-if="displayedRecipe.length > 0">
      <div i-mdi-bowl-mix-outline />
    </span>
    <span v-else>
      <div i-mdi-bowl-outline />
    </span>
  </button>
</template>
