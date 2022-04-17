<script lang="ts" setup>
import { useGtm } from '@gtm-support/vue-gtm'
import { isClient } from '@vueuse/core'
import { storeToRefs } from 'pinia'
import Switch from './Switch.vue'
import type { StuffItem } from '~/data/food'
import { meat, staple, tools, vegetable } from '~/data/food'
import recipeData from '~/data/recipe.json'
import type { Recipe } from '~/types'
import { useRecipeStore } from '~/stores/recipe'

import { useInvisibleElement } from '~/composables/helper'

const recipe = ref<Recipe>(recipeData as Recipe)

const rStore = useRecipeStore()
const { strict, curTool } = storeToRefs(rStore)
const curStuff = computed(() => rStore.selectedStuff)

// 默认严格模式
const displayedRecipe = computed(() => {
  const recipes = recipe.value.filter((item) => {
    if (strict.value) {
      const stuffFlag = curStuff.value.every(stuff => item.stuff.includes(stuff))
      const toolFlag = item.tools?.includes(curTool.value)
      return curTool.value ? stuffFlag && toolFlag : stuffFlag
    }
    else {
      const stuffFlag = curStuff.value.some(stuff => item.stuff.includes(stuff))
      const toolFlag = item.tools?.includes(curTool.value)

      // 同时存在 厨具和材料，则同时判断
      if (curTool.value && curStuff.value.length) {
        return stuffFlag && toolFlag
      }
      else {
        if (curStuff.value.length)
          return stuffFlag
        else if (curTool.value)
          return toolFlag

        return false
      }
    }
  })
  return recipes
})

const { x, y } = usePointer()

const recipeBtn = ref<HTMLButtonElement>()

const { top, left } = useElementBounding(recipeBtn)

const playAnimation = (emoji: string) => {
  if (!isClient)
    return

  // 单个 Vue 组件实现不适合创建多个元素和清除动画
  const emojiEl = document.createElement('span')
  emojiEl.style.position = 'fixed'
  emojiEl.style.left = `${x.value}px`
  emojiEl.style.top = `${y.value}px`
  emojiEl.style.zIndex = '10'
  emojiEl.style.transition = 'left .4s linear, top .4s cubic-bezier(0.5, -0.5, 1, 1)'
  emojiEl.textContent = emoji
  document.body.appendChild(emojiEl)

  setTimeout(() => {
    // 以防万一，按钮位置没检测出来，就不播放动画了
    if (!top.value || !left.value) {
      emojiEl.style.top = `${x.value}px`
      emojiEl.style.left = `${y.value}px`
    }
    else {
      emojiEl.style.top = `${top.value}px`
      emojiEl.style.left = `${left.value + 12}px`
    }
  }, 1)

  emojiEl.ontransitionend = () => {
    emojiEl.remove()
  }
}

const gtm = useGtm()

const toggleStuff = (item: StuffItem, category = '', e?: Event) => {
  rStore.toggleStuff(item.name)

  if (curStuff.value.includes(item.name))
    playAnimation(item.emoji)

  gtm?.trackEvent({
    event: 'click',
    category: `${category}_${item.name}`,
    action: 'click_stuff',
    label: '食材',
  })
  gtm?.trackEvent({
    event: 'click_stuff',
    action: item.name,
  })
}

/**
 * toggle tool
 * @param item
 */
const clickTool = (item: StuffItem) => {
  const value = item.name
  rStore.toggleTools(value)

  gtm?.trackEvent({
    event: 'click',
    category: `tool_${value}`,
    action: 'click_tool',
    label: '工具',
  })
  gtm?.trackEvent({
    event: 'click_tool',
    action: item.name,
  })
}

const recipePanel = ref()
const { isVisible, show } = useInvisibleElement(recipePanel)
</script>

<template>
  <Transition>
    <button
      v-show="displayedRecipe.length !== recipe.length && isVisible"
      ref="recipeBtn"
      class="cursor-pointer fixed inline-flex justify-center items-center rounded rounded-full shadow hover:shadow-md"
      bg="green-50 dark:green-900" w="10" h="10" bottom="4" right="4"
      text="green-600 dark:green-300"
      @click="show"
    >
      <span v-if="displayedRecipe.length">
        <div i-mdi-bowl-mix-outline />
      </span>
      <span v-else>
        <div i-mdi-bowl-outline />
      </span>
    </button>
  </Transition>

  <h2 m="t-4" text="xl" font="bold" p="1">
    🥘 先选一下食材
  </h2>
  <div>
    <h2 opacity="90" text="base" font="bold" p="1">
      🥬 菜菜们
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
    <h2 opacity="90" text="base" font="bold" p="1">
      🥩 肉肉们
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
    <h2 opacity="90" text="base" font="bold" p="1">
      🍚 主食也要一起下锅吗？（不选也行）
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
      🍳 再选一下厨具
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
          item.label || item.name
        }}
      </span>
    </ToolTag>
  </div>

  <div ref="recipePanel" m="2 t-4" p="2" class="transition shadow hover:shadow-md" bg="gray-400/8">
    <h2 text="xl" font="bold" p="1">
      🍲 来看看组合出的菜谱吧！
    </h2>
    <Switch />
    <div p="2">
      <Transition mode="out-in">
        <span v-if="!curStuff.length && !curTool" text="sm" p="2">
          你要先选食材或工具哦～
        </span>

        <span v-else-if="displayedRecipe.length">
          <DishTag v-for="item, i in displayedRecipe" :key="i" :dish="item" />
        </span>

        <span v-else text="sm">
          还没有完美匹配的菜谱呢……
          <br>
          大胆尝试一下，或者<a href="#" @click="rStore.reset()">
            <strong>换个组合</strong></a>？
        </span>
      </Transition>
    </div>
  </div>
</template>
