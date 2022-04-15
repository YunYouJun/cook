<script lang="ts" setup>
import { useGtm } from '@gtm-support/vue-gtm'
import MeatTag from './MeatTag.vue'
import StapleTag from './StapleTag.vue'
import DishTag from './DishTag.vue'
import type { StuffItem } from '~/data/food'
import { meat, staple, tools, vegetable } from '~/data/food'
import recipeData from '~/data/recipe.json'
import type { Recipe } from '~/types'
import { useRecipeStore } from '~/stores/recipe'
const recipe = ref(recipeData as Recipe)

const rStore = useRecipeStore()
const curStuff = computed(() => rStore.selectedStuff)
const curTools = computed(() => rStore.selectedTools)

// 默认严格模式
const strict = ref(true)
const displayedRecipe = computed(() => {
  return recipe.value.filter((item) => {
    if (strict.value) {
      const stuffFlag = curStuff.value.every(stuff => item.stuff.includes(stuff))
      // const toolFlag = curTools.value.every(tool => item.tools?.includes(tool))
      const toolFlag = curTools.value.some(tool => item.tools?.includes(tool))
      return stuffFlag && toolFlag
    }
    else {
      const stuffFlag = curStuff.value.some(stuff => item.stuff.includes(stuff))
      const toolFlag = curTools.value.some(tool => item.tools?.includes(tool))
      return stuffFlag || toolFlag
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
  const value = item.name
  rStore.toggleTools(value)

  gtm?.trackEvent({
    event: 'stuff',
    category: `tool_${value}`,
    action: 'click',
    label: '工具',
  })
}
</script>

<template>
  <div m="y-4">
    <h2 text="xl" font="bold" p="1">
      如果有 🥬 菜菜
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
      如果有 🥩 肉肉
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
      想来点 🍚 主食
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
  <div m="t-4">
    <h2 text="xl" font="bold" p="1">
      打算用 🍳 厨具
    </h2>
    <ToolTag
      v-for="item, i in tools" :key="i"
      :active="curTools.includes(item.name)"
      @click="clickTool(item)"
    >
      <span v-if="item.emoji" class="inline-flex">{{ item.emoji }}</span>
      <span v-else-if="item.icon" class="inline-flex">
        <div :class="item.icon" />
      </span>
      <span class="inline-flex" m="l-1">
        {{
          item.label || item.name
        }}
      </span>
    </ToolTag>
  </div>

  <!-- <div class="inline-flex justify-center items-center" m="y-4">
    <span :class="!strict && 'text-green-600'" font="bold" m="x-1" @click="strict = false">
      可做的所有菜
    </span>
    <label m="x-1" class="switch">
      <input v-model="strict" type="checkbox">
      <span class="inline-flex justify-center items-center slider round" />
    </label>
    <span :class="strict && 'text-green-600'" font="bold" m="x-1" @click="strict = true">
      一起做一道菜
    </span>
  </div> -->

  <div m="2" p="2" class="transition shadow hover:shadow-md" bg="gray-400/8">
    <h2 text="xl" font="bold" p="1">
      🍲 今天的菜
      <br>
      <small class="inline-flex justify-center items-center" text="xs">菜谱数据来源：
        <a class="inline-flex justify-center items-center" style="color: #ea7a99" href="https://www.bilibili.com/v/food" target="_blank">
          <div class="inline-flex" i-ri-bilibili-line />
          <span m="l-1" class="inline-flex" style="margin-top: 1px;">B 站</span>
        </a>
      </small>
    </h2>
    <Transition mode="out-in">
      <div v-if="displayedRecipe.length">
        <DishTag v-for="item, i in displayedRecipe" :key="i" :dish="item" />
      </div>
      <p v-else p="2">
        😢 还没有这样的食谱呢……
        <br>
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
  background-color: rgba(122,122,122,0.3);
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
  @apply bg-green-600;
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
