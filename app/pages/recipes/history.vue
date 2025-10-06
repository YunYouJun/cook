<script lang="ts" setup>
import type { RecipeItem } from '~/types'
import { Dialog } from '@capacitor/dialog'
import dayjs from 'dayjs'
import { recipeHistories } from '~/composables/store/history'

definePageMeta({
  layout: 'child',
  title: '历史记录',
})

// todo
// clear one history
async function clearAllHistory() {
  await Dialog.confirm({
    title: '清空历史记录',
    message: '确定要清空所有历史记录吗？此操作不可撤销。',
    okButtonTitle: '确认',
    cancelButtonTitle: '取消',
  }).then((result) => {
    if (result.value) {
      recipeHistories.value = []
    }
  })
}

function clearOneHistory(history: typeof recipeHistories.value[0]) {
  recipeHistories.value = recipeHistories.value.filter(h => h !== history)
}

function openDishLink(dish: RecipeItem) {
  const href = dish.link || `https://www.bilibili.com/video/${dish.bv}`
  window.open(href, '_blank')
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>历史记录</ion-title>

        <ion-buttons slot="end">
          <ion-button title="清空记录" @click="clearAllHistory">
            <ion-icon slot="icon-only" :icon="ioniconsTrashOutline" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list>
        <template v-for="history in recipeHistories" :key="history.recipe.name">
          <ion-item-sliding>
            <ion-item @click="openDishLink(history.recipe)">
              <ion-label class="truncate">
                <DishLabel class="text-sm" :dish="history.recipe" />
              </ion-label>
              <ion-text class="text-xs">
                {{ dayjs(history.time).format('YYYY-MM-DD HH:mm:ss') }}
              </ion-text>
            </ion-item>

            <ion-item-options>
              <!-- TODO -->
              <!-- <ion-item-option>
                收藏
              </ion-item-option> -->
              <ion-item-option color="danger" @click="clearOneHistory(history)">
                删除
              </ion-item-option>
            </ion-item-options>
          </ion-item-sliding>
        </template>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
