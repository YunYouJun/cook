<script lang="ts" setup>
import type { MaybeElementRef } from '@vueuse/core'
import type { StuffItem } from '~/types'
import { useEmojiAnimation } from '~/composables/animation'
import { useIncompatibleFoods } from '~/composables/incompatible-foods'
import { meat, staple, tools, vegetable } from '~/data/food'
import { yunleHaptic } from '~/utils/yunleHost'

const props = defineProps<{
  /** 选中食材时 emoji 飞向的位置（结果预览条） */
  flightTarget?: MaybeElementRef
}>()

const rStore = useRecipeStore()
const selected = computed(() => rStore.selectedStuff)
const pantryOpen = ref(false)

// 食物相克检测
const { warningMessage, hasWarning, checkIncompatibility } = useIncompatibleFoods()
watch(selected, checkIncompatibility, { deep: true })
onMounted(() => {
  if (selected.value.length)
    checkIncompatibility(selected.value)
})

const { playAnimation } = useEmojiAnimation(computed(() => unref(props.flightTarget)))
const { proxy } = useScriptGoogleTagManager()

const sections = [
  { title: '蔬菜', category: 'vegetable', items: vegetable },
  { title: '肉和蛋', category: 'meat', items: meat },
  { title: '主食（可选）', category: 'staple', items: staple },
]

function selectedNote(items: StuffItem[]) {
  const count = items.filter(item => selected.value.includes(item.name)).length
  return count ? `已选 ${count} 种` : ''
}

function toggleStuff(item: StuffItem, category: string, e: Event) {
  rStore.toggleStuff(item.name)
  if (selected.value.includes(item.name)) {
    const origin = e.currentTarget as Element | null
    void nextTick(() => playAnimation(item.emoji, origin))
    void yunleHaptic('light')
  }

  proxy.dataLayer.push({ event: 'click', category: `${category}_${item.name}`, action: 'click_stuff', label: '食材' })
  proxy.dataLayer.push({ event: 'click_stuff', action: item.name })
}

function clickTool(item: StuffItem) {
  rStore.clickTool(item)
  void yunleHaptic('light')
}
</script>

<template>
  <div class="choose-food">
    <IngredientInput :selected="selected" @add="rStore.addStuff" @remove="rStore.toggleStuff" />

    <IngredientSection v-for="section in sections" :key="section.category" :title="section.title" :note="selectedNote(section.items)">
      <IngredientChip
        v-for="item in section.items"
        :key="item.name"
        :label="item.name"
        :emoji="item.emoji"
        :selected="selected.includes(item.name)"
        @click="toggleStuff(item, section.category, $event)"
      />
    </IngredientSection>

    <IngredientSection title="厨具（可选）" :note="rStore.curTool ? '只看用它做的菜' : ''">
      <IngredientChip
        v-for="item in tools"
        :key="item.name"
        :label="item.name === '一口大锅' ? '炒锅' : (item.label || item.name)"
        :icon="item.icon"
        :selected="rStore.curTool === item.name"
        @click="clickTool(item)"
      />
    </IngredientSection>

    <h2 class="cook-group-header">
      调料
    </h2>
    <ion-list :inset="true" class="choose-food-pantry">
      <ion-item :button="true" @click="pantryOpen = true">
        <ion-label>家里常备</ion-label>
        <ion-note slot="end" class="choose-food-pantry-value">
          {{ rStore.selectedPantry.length ? rStore.selectedPantry.join('、') : '未选择' }}
        </ion-note>
      </ion-item>
    </ion-list>

    <PantrySheet
      v-model:open="pantryOpen"
      :selected="rStore.selectedPantry"
      @toggle="rStore.togglePantry"
      @clear="rStore.clearPantry"
      @reset="rStore.resetPantry"
    />

    <ion-toast
      class="incompatible-warning-toast"
      header="食物相克提醒"
      :message="warningMessage"
      :is-open="hasWarning"
      position="top"
      :icon="ioniconsWarningOutline"
    />
  </div>
</template>

<style scoped>
.choose-food {
  padding-bottom: 24px;
}
.choose-food-pantry-value {
  max-width: 60vw;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 17px;
}
</style>

<style>
ion-toast.incompatible-warning-toast {
  --background: rgba(255, 237, 213, 0.96);
  --color: #7c2d12;
  --box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}
.ion-palette-dark ion-toast.incompatible-warning-toast {
  --background: rgba(67, 32, 10, 0.96);
  --color: #fed7aa;
}
</style>
