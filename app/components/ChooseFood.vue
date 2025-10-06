<script lang="ts" setup>
import type { StuffItem } from '~/types'
import { storeToRefs } from 'pinia'
import { useEmojiAnimation } from '~/composables/animation'
import { useIncompatibleFoods } from '~/composables/incompatible-foods'

import { meat, staple, tools, vegetable } from '~/data/food'

const rStore = useRecipeStore()
const { curTool } = storeToRefs(rStore)
const curStuff = computed(() => rStore.selectedStuff)

// 食物相克检测
const { warningMessage, hasWarning, checkIncompatibility } = useIncompatibleFoods()

const recipeBtnRef = ref<HTMLButtonElement>()
const { playAnimation } = useEmojiAnimation(recipeBtnRef)

const { proxy } = useScriptGoogleTagManager()

const recipePanelRef = ref()

// 监听食材变化，自动检测相克
watch(curStuff, (newIngredients) => {
  checkIncompatibility(newIngredients)
}, { deep: true })

// 页面初始化时也检查一次（处理已有选择的情况）
onMounted(() => {
  if (curStuff.value.length > 0) {
    checkIncompatibility(curStuff.value)
  }
})

function toggleStuff(item: StuffItem, category = '', _e?: Event) {
  rStore.toggleStuff(item.name)

  if (curStuff.value.includes(item.name))
    playAnimation(item.emoji)

  proxy.dataLayer.push({
    event: 'click',
    category: `${category}_${item.name}`,
    action: 'click_stuff',
    label: '食材',
  })
  proxy.dataLayer.push({
    event: 'click_stuff',
    action: item.name,
  })
}
</script>

<template>
  <div>
    <h2 m="t-4" text="xl" font="bold" p="1">
      🥘 先选一下食材
    </h2>

    <!-- 食物相克警告提示 -->
    <ion-toast
      class="incompatible-warning-toast"
      :message="warningMessage"
      :is-open="hasWarning"
      position="top"
      :icon="ioniconsWarningOutline"
      animated
    >
      <div
        v-if="hasWarning"
        class="incompatible-warning-box"
        m="b-4" p="4"
        border="~ 2 red-300 dark:red-600 rounded-xl"
        text="red-800 dark:red-200 sm"
        shadow="lg"
        relative="~"
        overflow="hidden"
      >
        <div flex="~ items-start gap-3">
          <div text="2xl" flex="shrink-0" class="animate-pulse">
            🚨
          </div>
          <div flex="1 col gap-1">
            <div font="bold" text="base">
              食物相克警告！
            </div>
            <div leading="relaxed" whitespace="pre-line">
              {{ warningMessage }}
            </div>
          </div>
        </div>
      </div>
    </ion-toast>

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
        🍚 主食也要一起下锅吗？（不选也行）
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
    <div m="t-4">
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
    </div>

    <RecipePanel ref="recipePanelRef" />
  </div>

  <Transition>
    <BasketButton ref="recipeBtnRef" />
  </Transition>
</template>

<style>
ion-toast.incompatible-warning-toast {
  /* --background: #f4f4fa; */
  --box-shadow: 3px 3px 10px 0 rgba(0, 0, 0, 0.2);
  /* --color: #4b4a50; */
  --background: rgba(254, 202, 202, 0.95);
  --color: #7f1d1d;
}
</style>
