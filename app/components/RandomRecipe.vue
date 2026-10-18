<script lang="ts" setup>
const { count, inc, dec } = useCount()
const { random, randomRecipes } = useRandomRecipe(count)
const rStore = useRecipeStore()

/** 告诉用户这次是在什么范围里抽的 */
const scope = computed(() => {
  const parts: string[] = []
  if (rStore.selectedStuff.length)
    parts.push(`和 ${rStore.selectedStuff.join('、')} 搭得上的`)
  else if (rStore.curTool)
    parts.push(`用${rStore.curTool === '一口大锅' ? '炒锅' : rStore.curTool}做的`)
  if (rStore.methodGroups.length)
    parts.push(`做法：${rStore.methodGroups.join('、')}`)
  if (rStore.noSpicy)
    parts.push('不吃辣')
  if (rStore.selectedAvoid.length)
    parts.push(`避开 ${rStore.selectedAvoid.join('、')}`)
  return parts.join('，')
})
</script>

<template>
  <p v-if="scope" class="random-scope">
    按首页的选择：{{ scope }}（{{ rStore.randomPool.length }} 道里抽）
  </p>

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

  <ion-button :disabled="!rStore.isSearching && !rStore.randomPool.length" @click="random">
    <div class="transition" hover="text-blue-500" i-ri-refresh-line mr-1 inline-flex />
    <div>随机一下</div>
  </ion-button>

  <p v-if="!rStore.isSearching && !rStore.randomPool.length" class="random-scope">
    这个范围里没有菜，回首页放宽一下条件吧。
  </p>

  <div v-show="randomRecipes.length > 0">
    <div m="t-4" flex="~ col">
      <template v-for="recipe, i in randomRecipes" :key="i">
        <DishTag v-if="recipe" :dish="recipe" />
      </template>
    </div>
  </div>
</template>

<style scoped>
.random-scope {
  max-width: 80vw;
  margin: 12px 16px 0;
  text-align: center;
  font-size: 14px;
  color: var(--ion-color-medium);
}
</style>
