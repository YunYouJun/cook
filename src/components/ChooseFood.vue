<script lang="ts" setup>
import { storeToRefs } from 'pinia'
import { useGtm } from '@gtm-support/vue-gtm'
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

const gtm = useGtm()

const toggleStuff = (item: StuffItem, category = '') => {
  gtm?.trackEvent({
    event: 'stuff',
    category: `${category}_${item.name}`,
    action: 'click',
    label: '食材',
  })

  rStore.toggleStuff(item.name)
}

/**
 * toggle tool
 * @param item
 */
const clickTool = (item: StuffItem) => {
  if (curTool.value === item.name)
    curTool.value = ''
  else
    curTool.value = item.name

  gtm?.trackEvent({
    event: 'stuff',
    category: `tool_${item.name}`,
    action: 'click',
    label: '工具',
  })
}
</script>

<template>
  <div class="inline-flex justify-center items-center">
    <span :class="!strict && 'text-green-600'" font="bold" m="x-1" @click="strict = false">模糊匹配</span>
    <label m="x-1" class="switch">
      <input v-model="strict" type="checkbox">
      <span class="slider round" />
    </label>
    <span :class="strict && 'text-green-600'" font="bold" m="x-1" @click="strict = true">严格匹配</span>
  </div>

  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      🥬 菜菜
    </h2>
    <VegetableTag
      v-for="item, i in vegetable" :key="i"
      :active="curStuff.includes(item.name)"
      @click="toggleStuff(item, 'vegetable')"
    >
      <span v-if="item.emoji" class="inline-flex">{{ item.emoji }}</span>
      <span v-else-if="item.image" class="inline-flex">
        <img class="inline-flex" w="2" h="2" width="10" height="10" :src="item.image" :alt="item.name">
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
      @click="toggleStuff(item, 'meat')"
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
      @click="toggleStuff(item, 'staple')"
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
      @click="clickTool(item)"
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
          ❤️ 隔离食用手册大全 ❤️
        </a>
      </p>
    </Transition>
  </div>
</template>

<style lang="scss">
.switch {
  position: relative;
  display: inline-block;
  width: 48px;
  height: 28px;

  input {
    opacity: 0;
    width: 0;
    height: 0;
  }
}

.slider {
  position: absolute;
  cursor: pointer;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: #ccc;
  -webkit-transition: .4s;
  transition: .4s;
}

$size: 20px;

.slider:before {
  position: absolute;
  content: "";
  height: $size;
  width: $size;
  left: 4px;
  bottom: 4px;
  background-color: white;
  -webkit-transition: .4s;
  transition: .4s;
}

input:checked + .slider {
  background-color: green;
}

input:checked + .slider:before {
  -webkit-transform: translateX($size);
  -ms-transform: translateX($size);
  transform: translateX($size);
}

/* Rounded sliders */
.slider.round {
  border-radius: 28px;

  &:before {
    border-radius: 50%;
  }
}
</style>
