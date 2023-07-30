<script lang="ts" setup>
const { random, randomRecipe } = useRandomRecipe()
</script>

<template>
  <div v-show="randomRecipe">
    <div class="inline-flex items-center justify-center">
      <div>今天吃什么？</div>
      <div class="transition" hover="text-blue-500" i-ri-refresh-line inline-block cursor-pointer @click="random" />
    </div>
    <div m="t-2">
      <DishTag v-if="randomRecipe" :dish="randomRecipe" />
    </div>
  </div>
</template>
