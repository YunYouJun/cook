<script lang="ts" setup>
const rStore = useRecipeStore()
</script>

<template>
  <div class="inline-flex items-center justify-center">
    今天吃什么？<div class="transition" hover="text-blue-500" i-ri-refresh-line inline-block cursor-pointer @click="rStore.random" />
  </div>
  <div m="t-2">
    <DishTag :dish="rStore.randomRecipe" />
  </div>
</template>
