<script lang="ts" setup>
import type { MaybeElementRef } from '@vueuse/core'
import type { StuffItem } from '~/types'
import { useStorage } from '@vueuse/core'
import { DIFFICULTY_LEVELS, METHOD_GROUPS } from '@yunyoujun/cook/recommend'
import { useEmojiAnimation } from '~/composables/animation'
import { useIncompatibleFoods } from '~/composables/incompatible-foods'
import { namespace } from '~/constants'
import { methodIcons, toolIcons } from '~/data/cookingIcons'
import { pantryOptions } from '~/data/pantry'
import { trackCookEvent } from '~/utils/analytics'
import { yunleHaptic } from '~/utils/yunleHost'

const props = defineProps<{
  desktop?: boolean
  /** 选中食材时 emoji 飞向的位置（结果预览条） */
  flightTarget?: MaybeElementRef
}>()

const rStore = useRecipeStore()
const selected = computed(() => rStore.selectedStuff)
const pantryOpen = ref(false)
const avoidOpen = ref(false)

// 食物相克检测
const { warningMessage, hasWarning, checkIncompatibility } = useIncompatibleFoods()
watch(selected, checkIncompatibility, { deep: true })
onMounted(() => {
  if (selected.value.length)
    checkIncompatibility(selected.value)
})

const { playAnimation } = useEmojiAnimation(computed(() => unref(props.flightTarget)))

const { sections, food } = usePersonalCatalog()
const tools = computed<StuffItem[]>(() => {
  const visible = food.value.tools
  return rStore.curTool && !visible.some(item => item.name === rStore.curTool)
    ? [...visible, { name: rStore.curTool, emoji: '🍳' }]
    : visible
})

/** 展开了「更多」的分组，记住用户的选择 */
const expanded = useStorage<string[]>(`${namespace}:food-more-expanded`, [])

/** 宽屏完整展示；窄屏保留用户的展开偏好和已选食材。 */
function visibleItems(items: StuffItem[], category: string) {
  if (props.desktop || expanded.value.includes(category))
    return items
  return items.filter(item => !item.more || selected.value.includes(item.name))
}

function hiddenCount(items: StuffItem[], category: string) {
  return items.length - visibleItems(items, category).length
}

function toggleMore(category: string) {
  expanded.value = expanded.value.includes(category)
    ? expanded.value.filter(c => c !== category)
    : [...expanded.value, category]
}

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

  trackCookEvent('click_stuff')
}

function clickTool(item: StuffItem) {
  rStore.clickTool(item)
  void yunleHaptic('light')
}
</script>

<template>
  <div class="choose-food" :class="{ 'choose-food-desktop': desktop }">
    <IngredientInput
      :selected="selected"
      :parse="rStore.parseText"
      placeholder="食材或一句话，如：土豆鸡蛋，不要辣"
      @add="rStore.addStuff"
      @remove="rStore.toggleStuff"
      @apply="rStore.applyQuery"
    />

    <div class="choose-food-categories">
      <IngredientSection v-for="section in sections" :key="section.category" :title="section.title" :note="selectedNote(section.items)">
        <IngredientChip
          v-for="item in visibleItems(section.items, section.category)"
          :key="item.name"
          :label="item.name"
          :emoji="item.emoji"
          :selected="selected.includes(item.name)"
          @click="toggleStuff(item, section.category, $event)"
        />
        <IngredientChip
          v-if="!desktop && (expanded.includes(section.category) || hiddenCount(section.items, section.category) > 0)"
          :label="expanded.includes(section.category) ? '收起' : `更多 ${hiddenCount(section.items, section.category)} 种`"
          :icon="expanded.includes(section.category) ? 'i-ri-arrow-up-s-line' : 'i-ri-arrow-down-s-line'"
          :selected="false"
          :aria-pressed="undefined"
          :aria-expanded="expanded.includes(section.category)"
          class="choose-food-more"
          @click="toggleMore(section.category)"
        />
      </IngredientSection>
    </div>

    <ion-button fill="clear" router-link="/catalog">
      管理食材和厨具
    </ion-button>
    <IngredientSection title="厨具（可选）" :note="rStore.curTool ? '只看用它做的菜' : ''">
      <IngredientChip
        v-for="item in tools"
        :key="item.name"
        :label="item.name === '一口大锅' ? '炒锅' : (item.label || item.name)"
        :icon="toolIcons[item.name] || item.icon"
        :selected="rStore.curTool === item.name"
        @click="clickTool(item)"
      />
    </IngredientSection>

    <IngredientSection title="做法（可选）" :note="rStore.methodGroups.length ? '只看这些做法' : ''">
      <IngredientChip
        v-for="group in METHOD_GROUPS"
        :key="group.label"
        :label="group.label"
        :icon="methodIcons[group.label]"
        :selected="rStore.methodGroups.includes(group.label)"
        @click="rStore.toggleMethodGroup(group.label)"
      />
    </IngredientSection>

    <IngredientSection title="难度（可选）" :note="rStore.difficulty.length ? '没标难度的菜会被隐藏' : ''">
      <IngredientChip
        v-for="level in DIFFICULTY_LEVELS"
        :key="level"
        :label="level"
        :selected="rStore.difficulty.includes(level)"
        @click="rStore.toggleDifficulty(level)"
      />
    </IngredientSection>

    <IngredientSection v-if="desktop" title="家里常备" :note="`已选 ${rStore.selectedPantry.length} 种调料`">
      <IngredientChip
        v-for="name in pantryOptions"
        :key="name"
        :label="name"
        :selected="rStore.selectedPantry.includes(name)"
        @click="rStore.togglePantry(name)"
      />
      <div class="choose-food-pantry-actions">
        <button type="button" @click="rStore.resetPantry">
          恢复默认
        </button>
        <button type="button" :disabled="!rStore.selectedPantry.length" @click="rStore.clearPantry">
          全部取消
        </button>
      </div>
    </IngredientSection>
    <p v-if="desktop" class="cook-group-footer choose-food-pantry-note">
      只在菜谱明确列出时算作已有，不会额外增加必需的材料。
    </p>

    <h2 class="cook-group-header">
      {{ desktop ? '忌口' : '调料和忌口' }}
    </h2>
    <ion-list :inset="true" class="choose-food-pantry">
      <ion-item v-if="!desktop" :button="true" @click="pantryOpen = true">
        <ion-label>家里常备</ion-label>
        <ion-note slot="end" class="choose-food-pantry-value">
          {{ rStore.selectedPantry.length ? rStore.selectedPantry.join('、') : '未选择' }}
        </ion-note>
      </ion-item>
      <ion-item :button="true" @click="avoidOpen = true">
        <ion-label>忌口</ion-label>
        <ion-note slot="end" class="choose-food-pantry-value">
          {{ rStore.selectedAvoid.length ? rStore.selectedAvoid.join('、') : '无' }}
        </ion-note>
      </ion-item>
      <ion-item>
        <ion-toggle v-model="rStore.noSpicy" justify="space-between">
          不吃辣
        </ion-toggle>
      </ion-item>
    </ion-list>

    <PantrySheet
      v-model:open="pantryOpen"
      :selected="rStore.selectedPantry"
      @toggle="rStore.togglePantry"
      @clear="rStore.clearPantry"
      @reset="rStore.resetPantry"
    />

    <AvoidSheet
      v-model:open="avoidOpen"
      :selected="rStore.selectedAvoid"
      @toggle="rStore.toggleAvoid"
      @clear="rStore.clearAvoid"
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
.choose-food-more {
  background: transparent;
  color: var(--ion-color-primary);
}
.choose-food-pantry-value {
  max-width: 60vw;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 17px;
}
.choose-food-desktop {
  --ingredient-chip-height: 32px;
  --ingredient-chip-padding: 5px 9px;
  --ingredient-chip-radius: 7px;
  --ingredient-chip-font-size: 14px;
  --ingredient-section-heading-margin: 18px 0 0;
  --ingredient-section-heading-padding: 0 2px 7px;
  --ingredient-section-heading-color: var(--ion-text-color);
  --ingredient-section-heading-weight: 600;
  --ingredient-section-gap: 6px;
  --ingredient-section-margin: 0;
  --ingredient-section-padding: 10px;
  --ingredient-section-border: 1px solid var(--ion-color-step-150, rgba(0, 0, 0, 0.12));
  --ingredient-section-radius: 8px;
  padding: 0 16px 24px;
}
.choose-food-desktop .choose-food-categories {
  gap: 0 14px;
}
.choose-food-desktop .choose-food-pantry-value {
  max-width: 60%;
  font-size: 14px;
}
.choose-food-desktop > ion-button {
  --padding-start: 0;
  --padding-end: 0;
  margin: 6px 0 0;
  min-height: 30px;
  font-size: 13px;
}
.choose-food-desktop > .cook-group-header {
  margin-top: 18px;
  padding: 0 2px 7px;
  color: var(--ion-text-color);
  font-weight: 600;
}
.choose-food-desktop .choose-food-pantry {
  margin: 0;
  border: 1px solid var(--ion-color-step-150, rgba(0, 0, 0, 0.12));
  border-radius: 8px;
}
.choose-food-desktop .choose-food-pantry ion-item {
  --min-height: 38px;
  font-size: 14px;
}
.choose-food-desktop .choose-food-pantry-actions button {
  min-height: 30px;
  font-size: 13px;
}
.choose-food-desktop .choose-food-pantry-note {
  padding: 0 2px;
  font-size: 12px;
}
.choose-food-desktop :deep(.ingredient-input-field) {
  --border-radius: 7px;
  padding-inline: 0;
}
.choose-food-desktop :deep(.ingredient-input-row) {
  flex-wrap: wrap;
  padding-inline: 0;
}
.choose-food-desktop :deep(.ingredient-token) {
  height: 26px;
  border-radius: 6px;
  font-size: 13px;
}
.choose-food-desktop :deep(.ingredient-chip:hover:not([aria-pressed='true'])) {
  background: var(--ion-color-step-200, rgba(118, 118, 128, 0.2));
}
.choose-food-pantry-actions {
  display: flex;
  flex-basis: 100%;
  gap: 16px;
}
.choose-food-pantry-actions button {
  min-height: 44px;
  color: var(--ion-color-primary);
  font-size: 14px;
}
.choose-food-pantry-actions button:disabled {
  opacity: 0.4;
}
.choose-food-pantry-actions button:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 3px;
  border-radius: 4px;
}
.choose-food-pantry-note {
  margin-top: 10px;
}
@media (min-width: 1280px) {
  .choose-food-desktop .choose-food-categories {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }
  .choose-food-desktop .choose-food-categories > :first-child {
    grid-column: 1 / -1;
  }
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
