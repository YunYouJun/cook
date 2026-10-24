<script lang="ts" setup>
import type { RecipeHistoryItem } from '~/composables/store/history'
import dayjs from 'dayjs'
import RecipeFeedbackButton from '~/components/feedback/RecipeButton.vue'
import { clearRecipeHistories, recipeHistories, removeRecipeHistory } from '~/composables/store/history'
import { confirmDestructive } from '~/utils/dialog'
import { openRecipe } from '~/utils/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  layout: 'child',
  title: '历史记录',
})

/**
 * 按天分组，最近的在最前
 */
const sections = computed(() => {
  const today = dayjs().startOf('day')
  const groups: { label: string, items: RecipeHistoryItem[] }[] = []
  const sorted = [...recipeHistories.value].sort((a, b) => b.time - a.time)
  for (const history of sorted) {
    const day = dayjs(history.time).startOf('day')
    const diff = today.diff(day, 'day')
    const label = diff === 0 ? '今天' : diff === 1 ? '昨天' : day.year() === today.year() ? day.format('M 月 D 日') : day.format('YYYY 年 M 月 D 日')
    const last = groups.at(-1)
    if (last?.label === label)
      last.items.push(history)
    else
      groups.push({ label, items: [history] })
  }
  return groups
})

async function clearAllHistory() {
  if (await confirmDestructive({ header: '历史记录清空后不可恢复', confirmText: '清空历史记录' })) {
    clearRecipeHistories()
    void yunleHaptic('success')
  }
}

function clearOneHistory(history: RecipeHistoryItem) {
  removeRecipeHistory(history)
  void yunleHaptic('medium')
}
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>历史记录</ion-title>

        <ion-buttons slot="end">
          <ion-button title="清空记录" :disabled="!recipeHistories.length" @click="clearAllHistory">
            <ion-icon slot="icon-only" :icon="ioniconsTrashOutline" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true">
      <ion-list v-if="sections.length">
        <ion-item-group v-for="section in sections" :key="section.label">
          <ion-item-divider sticky>
            <ion-label>{{ section.label }}</ion-label>
          </ion-item-divider>
          <TransitionGroup name="cook-row" tag="div" class="cook-row-group">
            <ion-item-sliding v-for="history in section.items" :key="`${history.time}-${history.recipe.name}`">
              <ion-item :button="true" :detail="false" @click="openRecipe(history.recipe)">
                <ion-label class="truncate">
                  <DishLabel class="text-sm" :dish="history.recipe" />
                </ion-label>
                <RecipeFeedbackButton slot="end" :recipe="history.recipe" compact />
                <ion-note slot="end" class="text-xs">
                  {{ dayjs(history.time).format('HH:mm') }}
                </ion-note>
              </ion-item>

              <ion-item-options side="end" @ion-swipe="clearOneHistory(history)">
                <ion-item-option color="danger" :expandable="true" @click="clearOneHistory(history)">
                  删除
                </ion-item-option>
              </ion-item-options>
            </ion-item-sliding>
          </TransitionGroup>
        </ion-item-group>
      </ion-list>

      <div v-else class="cook-empty">
        <ion-icon :icon="ioniconsTimeOutline" />
        <h3>还没有历史记录</h3>
        <p>打开过的菜谱会出现在这里</p>
      </div>
    </ion-content>
  </ion-page>
</template>

<style scoped>
ion-item-divider {
  --background: var(--ion-background-color);
  --color: var(--ion-color-step-600, #666);
  min-height: 32px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: none;
}
</style>
