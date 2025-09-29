<script lang="ts" setup>
const { count, inc, dec } = useCount()
const { random, randomRecipes } = useRandomRecipe(count)
</script>

<template>
  <div inline-flex m="y-3">
    <button btn p-2 rounded-full @click="dec()">
      <div i-carbon-subtract />
    </button>
    <div font="mono" w="15" m-auto inline-block>
      {{ count }}
    </div>
    <button btn p-2 rounded-full @click="inc()">
      <div i-carbon-add />
    </button>
  </div>

  <button cursor-pointer class="text-sm text-white leading-6 font-semibold px-3 py-1.5 rounded-md border-none bg-blue-600 inline-flex inline-flex shadow-sm items-center justify-center focus-visible:outline-2 focus-visible:outline-blue-600 focus-visible:outline-offset-2 focus-visible:outline hover:bg-blue-500" @click="random">
    <div class="transition" hover="text-blue-500" i-ri-refresh-line mr-1 inline-flex />
    <div>随机一下</div>
  </button>

  <div v-show="randomRecipes.length > 0">
    <div m="t-8" flex="~ col">
      <template v-for="recipe, i in randomRecipes" :key="i">
        <DishTag v-if="recipe" :dish="recipe" />
      </template>
    </div>
  </div>
</template>
