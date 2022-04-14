<script lang="ts" setup>
import { storeToRefs } from 'pinia'
import MeatTag from './MeatTag.vue'
import StapleTag from './StapleTag.vue'
import DishTag from './DishTag.vue'
import type { StuffItem } from '~/data/foot'
import { meat, staple, tools, vegetable } from '~/data/foot'
import recipeData from '~/data/recipe.json'
import type { Recipe } from '~/types'
import { useRecipeStore } from '~/stores/recipe'
const recipe = ref(recipeData as Recipe)

const rStore = useRecipeStore()
const curStuff = computed(() => rStore.selectedStuff)
const { curTool } = storeToRefs(rStore)

const strict = ref(false)
const displayedRecipe = computed(() => {
  return recipe.value.filter((item) => {
    if (strict.value) {
      const stuffFlag = curStuff.value.every(stuff => item.stuff.includes(stuff))
      return curTool.value ? stuffFlag && item.tools?.includes(curTool.value) : stuffFlag
    }
    else {
      const stuffFlag = curStuff.value.some(stuff => item.stuff.includes(stuff))
      return stuffFlag || item.tools?.includes(curTool.value)
    }
  })
})

const toggleStuff = (item: StuffItem) => {
  rStore.toggleStuff(item.name)
  if (item.alias)
    rStore.toggleStuff(item.alias)
}
</script>

<template>
  <button m="x-1" class="btn" :bg="strict && 'orange-500 hover:orange-600'" @click="strict = !strict">
    <span v-if="strict">
      严格模式
    </span>
    <span v-else>
      宽松模式
    </span>
  </button>
  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      🥬 菜菜
    </h2>
    <VegetableTag
      v-for="item, i in vegetable" :key="i"
      :active="curStuff.includes(item.name)"
      @click="toggleStuff(item)"
    >
      <span v-if="item.emoji" class="inline-flex">{{ item.emoji }}</span>
      <span v-else-if="item.image" class="inline-flex">
        <img class="inline-flex" width="10" height="10" :src="item.image" :alt="item.name">
      </span>
      <span class="inline-flex" m="l-1">
        {{
          item.name
        }}
      </span>
    </VegetableTag>
  </div>
  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      🥩 肉肉
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
      🍚 主食
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
  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      🔧 工具
    </h2>
    <ToolTag
      v-for="item, i in tools" :key="i"
      :active="curTool === item.name"
      @click="curTool === item.name ? curTool = '' : curTool = item.name"
    >
      <span v-if="item.emoji" class="inline-flex">{{ item.emoji }}</span>
      <span v-else-if="item.icon" class="inline-flex">
        <div :class="item.icon" />
      </span>
      <span class="inline-flex" m="l-1">
        {{
          item.name
        }}
      </span>
    </ToolTag>
  </div>
  <div p="2 y-3" m="2" class="transition shadow hover:shadow-md" bg="gray-400/8">
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
        <a class="text-sm text-blue-600 dark:text-blue-400" href="https://docs.qq.com/sheet/DZUpJS0tQZm1YYWlt" target="_blank">
          隔离食用手册大全
        </a>
      </p>
    </Transition>
  </div>
</template>
