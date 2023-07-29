<script lang="ts" setup>
defineProps({
  isVisible: Boolean,
})

const rStore = useRecipeStore()
</script>

<template>
  <button
    v-show="rStore.displayedRecipe.length !== rStore.recipes.length && isVisible"
    class="fixed z-9 inline-flex cursor-pointer items-center justify-center rounded rounded-full shadow hover:shadow-md"
    bg="green-50 dark:green-900" w="10" h="10"
    bottom="18" right="4"
    text="green-600 dark:green-300"
  >
    <span v-if="rStore.displayedRecipe.length">
      <div i-mdi-bowl-mix-outline />
    </span>
    <span v-else>
      <div i-mdi-bowl-outline />
    </span>
  </button>
</template>
