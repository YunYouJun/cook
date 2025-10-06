<script lang="ts" setup>
const { count, inc, dec } = useCount()
const { random, randomRecipes } = useRandomRecipe(count)
</script>

<template>
  <div inline-flex m="y-3">
    <ion-button shape="round" @click="dec()">
      <ion-icon slot="icon-only" :icon="ioniconsRemoveOutline" />
    </ion-button>
    <div font="mono" class="w-15 text-center text-2xl" m-auto>
      {{ count }}
    </div>
    <ion-button shape="round" @click="inc()">
      <ion-icon slot="icon-only" :icon="ioniconsAddOutline" />
    </ion-button>
  </div>

  <ion-button @click="random">
    <div class="transition" hover="text-blue-500" i-ri-refresh-line mr-1 inline-flex />
    <div>随机一下</div>
  </ion-button>

  <div v-show="randomRecipes.length > 0">
    <div m="t-4" flex="~ col">
      <template v-for="recipe, i in randomRecipes" :key="i">
        <DishTag v-if="recipe" :dish="recipe" />
      </template>
    </div>
  </div>
</template>
