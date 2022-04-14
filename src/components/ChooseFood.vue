<script lang="ts" setup>
import MeatTag from './MeatTag.vue'
import StapleTag from './StapleTag.vue'
import DishTag from './DishTag.vue'
import type { StuffItem } from '~/data/foot'
import { meat, staple, vegetable } from '~/data/foot'
import recipeData from '~/data/recipe.json'
import type { Recipe } from '~/types'
import { useRecipeStore } from '~/stores/recipe'
const recipe = ref(recipeData as Recipe)

const rStore = useRecipeStore()
const curStuff = computed(() => rStore.selectedStuff)

const displayedRecipe = computed(() => {
  return recipe.value.filter((item) => {
    return curStuff.value.some(stuff => item.stuff.includes(stuff))
  })
})

const toggleStuff = (item: StuffItem) => {
  rStore.toggleStuff(item.name)
  if (item.alias)
    rStore.toggleStuff(item.alias)
}
</script>

<template>
  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      🥬 蔬菜区
    </h2>
    <VegetableTag
      v-for="item, i in vegetable" :key="i"
      :active="curStuff.includes(item.name)"
      @click="toggleStuff(item)"
    >
      <span v-if="item.emoji">{{ item.emoji }}</span>
      <img v-else-if="item.image" class="inline-flex" width="12" w="3" :src="item.image">
      <span m="l-1">
        {{
          item.name
        }}
      </span>
    </VegetableTag>
  </div>
  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      🥩 荤菜区
    </h2>
    <MeatTag
      v-for="item, i in meat" :key="i"
      :active="curStuff.includes(item.name)"
      @click="toggleStuff(item)"
    >
      <span>{{ item.emoji }}</span>
      <span m="l-1">
        {{
          item.name
        }}
      </span>
    </MeatTag>
  </div>
  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      🍚 主食区
    </h2>
    <StapleTag
      v-for="item, i in staple" :key="i"
      :active="curStuff.includes(item.name)"
      @click="toggleStuff(item)"
    >
      <span>{{ item.emoji }}</span>
      <span m="l-1">
        {{
          item.name
        }}
      </span>
    </StapleTag>
  </div>
  <hr p="2" opacity="10">
  <div>
    <h2 text="xl" font="bold" p="1">
      📄 菜谱
    </h2>
    <Transition mode="out-in">
      <div v-if="displayedRecipe.length">
        <DishTag v-for="item, i in displayedRecipe" :key="i" :dish="item" />
      </div>
      <p v-else p="2">
        😢 还没有这样的食谱呢……
        <br>
        <a class="text-sm text-blue-500" href="https://docs.qq.com/sheet/DZUpJS0tQZm1YYWlt" target="_blank">
          隔离食用手册大全
        </a>
      </p>
    </Transition>
  </div>
</template>
