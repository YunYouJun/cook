<script lang="ts" setup>
const { count, inc, dec } = useCount()
const { random, randomRecipes } = useRandomRecipe(count)
</script>

<template>
  <div inline-flex m="y-3">
    <button rounded-full p-2 btn @click="dec()">
      <div i-carbon-subtract />
    </button>
    <div font="mono" w="15" m-auto inline-block>
      {{ count }}
    </div>
    <button rounded-full p-2 btn @click="inc()">
      <div i-carbon-add />
    </button>
  </div>

  <div v-show="randomRecipes.length > 0">
    <button cursor-pointer class="inline-flex inline-flex items-center justify-center rounded-md border-none bg-blue-600 px-3 py-1.5 text-sm font-semibold leading-6 text-white shadow-sm hover:bg-blue-500 focus-visible:outline-2 focus-visible:outline-blue-600 focus-visible:outline-offset-2 focus-visible:outline" @click="random">
      <div class="transition" hover="text-blue-500" i-ri-refresh-line mr-1 inline-flex />
      <div>随机一下</div>
    </button>
    <div m="t-8" flex="~ col">
      <template v-for="recipe, i in randomRecipes" :key="i">
        <DishTag v-if="recipe" :dish="recipe" />
      </template>
    </div>
  </div>
</template>
