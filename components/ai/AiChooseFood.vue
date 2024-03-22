<script lang="ts" setup>
import type { StuffItem } from '~/types'
import { meat, staple, vegetable } from '~/data/food'

import { useEmojiAnimation } from '~/composables/animation'
import type { AIRecipeInfo } from '~/packages/ai/src'
import { generateRecipeInfo, getRecipeImage } from '~/utils/api'

const rStore = useRecipeStore()
const curStuff = computed(() => rStore.selectedStuff)

const recipeBtnRef = ref<HTMLButtonElement>()
const { playAnimation } = useEmojiAnimation(recipeBtnRef)

const gtm = useGtm()
const recipePanelRef = ref()
const { isVisible, show } = useInvisibleElement(recipePanelRef)

function toggleStuff(item: StuffItem, category = '', _e?: Event) {
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

// cook recipe
const cooking = ref(false)
const recipeImg = ref('')

const aiRecipeInfo = ref<AIRecipeInfo>({
  名称: '名称',
  介绍: '介绍',
})

async function cook() {
  cooking.value = true
  const foods = rStore.selectedStuff

  // reset
  aiRecipeInfo.value = ({
    名称: '起名中...',
    介绍: '正在思考怎么介绍...',
  })
  recipeImg.value = ''

  // generate
  const [info, img] = await Promise.all([generateRecipeInfo(foods), getRecipeImage(foods)])
  aiRecipeInfo.value = info
  recipeImg.value = img

  cooking.value = false
}
</script>

<template>
  <div>
    <h2 m="t-4" text="xl" font="bold" p="1">
      🥘 先选一下食材
    </h2>
    <div>
      <h2 opacity="90" text="base" font="bold" p="1">
        🥬 菜菜们
      </h2>
      <div>
        <VegetableTag
          v-for="item, i in vegetable" :key="i"
          :active="curStuff.includes(item.name)"
          @click="toggleStuff(item, 'vegetable')"
        >
          <span v-if="item.emoji" class="inline-flex">{{ item.emoji }}</span>
          <span v-else-if="item.image" class="inline-flex">
            <img class="inline-flex" w="2" h="2" width="10" height="10" :src="item.image" :alt="item.name">
          </span>
          <span class="inline-flex" m="l-1">{{ item.name }}</span>
        </VegetableTag>
      </div>
    </div>
    <div m="y-4">
      <h2 opacity="90" text="base" font="bold" p="1">
        🥩 肉肉们
      </h2>
      <div>
        <MeatTag
          v-for="item, i in meat" :key="i"
          :active="curStuff.includes(item.name)"
          @click="toggleStuff(item, 'meat')"
        >
          <span>{{ item.emoji }}</span>
          <span m="l-1">{{ item.name }}</span>
        </MeatTag>
      </div>
    </div>
    <div m="y-4">
      <h2 opacity="90" text="base" font="bold" p="1">
        🍚 主食
      </h2>
      <div>
        <StapleTag
          v-for="item, i in staple" :key="i"
          :active="curStuff.includes(item.name)"
          @click="toggleStuff(item, 'staple')"
        >
          <span>{{ item.emoji }}</span>
          <span m="l-1">{{ item.name }}</span>
        </StapleTag>
      </div>
    </div>
    <!-- <div m="t-4">
      <h2 text="xl" font="bold" p="1">
        🍳 再选一下厨具
      </h2>
      <div>
        <ToolTag
          v-for="item, i in tools" :key="i"
          :active="curTool === item.name"
          @click="rStore.clickTool(item)"
        >
          <span v-if="item.emoji" class="inline-flex">
            {{ item.emoji }}
          </span>
          <span v-else-if="item.icon" class="inline-flex">
            <div :class="item.icon" />
          </span>
          <span class="inline-flex" m="l-1">{{ item.label || item.name }}</span>
        </ToolTag>
      </div>
    </div> -->

    <Transition>
      <BasketButton ref="recipeBtnRef" :is-visible="isVisible" @click="show" />
    </Transition>

    <button
      m-auto
      flex items-center justify-center
      class="rounded bg-yellow px-4 py-2 text-orange-900 font-black shadow hover:shadow-md active:shadow-inset"
      @click="cook()"
    >
      <div v-if="cooking" class="mr-2 inline-flex" i-svg-spinners:clock />
      <span>做黑暗料理 🥘</span>
    </button>

    <div
      class="recipe-panel relative shadow transition"
      m="x-2 y-4" p="2"
      bg="gray-400/8"
    >
      <div text="xl" font="bold" p="1">
        「{{ aiRecipeInfo['名称'] }}」
      </div>

      <div class="cook-recipes text-center" p="2">
        <img
          v-if="recipeImg"
          class="m-auto w-25 rounded shadow transition hover:shadow-md"
          :src="recipeImg"
          alt="recipes"
        >
        <div v-else class="m-auto h-25 w-25 rounded bg-gray shadow transition hover:shadow-md" />
      </div>

      <div>
        {{ aiRecipeInfo['介绍'] }}
      </div>
    </div>
  </div>
</template>
