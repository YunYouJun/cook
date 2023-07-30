<script lang="ts" setup>
import { storeToRefs } from 'pinia'
import type { StuffItem } from '~/data/food'
import { meat, staple, tools, vegetable } from '~/data/food'

import { useEmojiAnimation } from '~/composables/animation'

const rStore = useRecipeStore()
const { curTool } = storeToRefs(rStore)
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

    <Transition>
      <BasketButton ref="recipeBtnRef" :is-visible="isVisible" @click="show" />
    </Transition>
    <RecipePanel ref="recipePanelRef" />
  </div>
</template>
