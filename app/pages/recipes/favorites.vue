<script lang="ts" setup>
import type { IonSearchbarCustomEvent, SearchbarInputEventDetail } from '@ionic/core'
import type { DbRecipeItem } from '~/utils/db'
import { getFavoriteTime, isFavorited, toggleFavorite } from '~/composables/store/favorite'
import { db } from '~/utils/db'
import { confirmDestructive } from '~/utils/dialog'
import { openRecipe } from '~/utils/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  layout: 'child',
  title: '我的收藏',
})

const loading = ref(true)
const recipes = shallowRef<DbRecipeItem[]>([])
const keyword = ref('')
const sortKey = ref<'time' | 'name'>('time')

const displayed = computed(() => {
  const text = keyword.value.trim()
  let list = recipes.value
  if (text)
    list = list.filter(r => r.name.includes(text))

  if (sortKey.value === 'name')
    return [...list].sort((a, b) => a.name.localeCompare(b.name))

  // time: sort by recorded favorite timestamp (recent first)
  return [...list].sort((a, b) => {
    const ta = getFavoriteTime(a) ?? -Infinity
    const tb = getFavoriteTime(b) ?? -Infinity
    return tb - ta
  })
})

async function loadFavorites() {
  try {
    const all = await db.recipes.toArray()
    recipes.value = all.filter(r => isFavorited(r))
  }
  finally {
    loading.value = false
  }
}

onMounted(loadFavorites)

async function clearAllFavorites() {
  if (!await confirmDestructive({ header: '将取消所有收藏', confirmText: '清空收藏' }))
    return
  for (const item of recipes.value)
    toggleFavorite(item)
  void yunleHaptic('success')
  await loadFavorites()
}

function onToggleFavorite(item: DbRecipeItem) {
  toggleFavorite(item)
  void yunleHaptic('medium')
  // update list immediately
  recipes.value = recipes.value.filter(r => isFavorited(r))
}
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>我的收藏</ion-title>

        <ion-buttons slot="end">
          <ion-button title="清空收藏" :disabled="!recipes.length" @click="clearAllFavorites">
            <ion-icon slot="icon-only" :icon="ioniconsTrashOutline" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>

      <ion-toolbar>
        <ion-searchbar
          placeholder="搜索收藏"
          :debounce="150"
          show-clear-button="focus"
          @ion-input="(ev: IonSearchbarCustomEvent<SearchbarInputEventDetail>) => (keyword = ev.detail.value ?? '')"
        />
      </ion-toolbar>

      <ion-toolbar class="favorites-segment">
        <ion-segment
          :value="sortKey"
          @ion-change="e => (sortKey = (e.detail.value as 'time' | 'name') ?? 'time')"
        >
          <ion-segment-button value="time">
            <ion-label>按收藏时间</ion-label>
          </ion-segment-button>
          <ion-segment-button value="name">
            <ion-label>按名称</ion-label>
          </ion-segment-button>
        </ion-segment>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true">
      <ion-list v-if="loading" aria-busy="true" aria-label="正在加载收藏">
        <ion-item v-for="i in 6" :key="i">
          <ion-label>
            <ion-skeleton-text :animated="true" :style="{ width: `${40 + (i * 37) % 45}%` }" />
          </ion-label>
        </ion-item>
      </ion-list>

      <ion-list v-else-if="displayed.length">
        <TransitionGroup name="cook-row" tag="div" class="cook-row-group">
          <ion-item-sliding v-for="item in displayed" :key="item.id ?? item.name">
            <ion-item :button="true" :detail="false" @click="openRecipe(item)">
              <ion-label class="truncate">
                <DishLabel class="text-sm" :dish="item" />
              </ion-label>
              <ion-icon slot="end" :icon="ioniconsStar" color="warning" />
            </ion-item>

            <ion-item-options side="end" @ion-swipe="onToggleFavorite(item)">
              <ion-item-option color="medium" :expandable="true" @click="onToggleFavorite(item)">
                取消收藏
              </ion-item-option>
            </ion-item-options>
          </ion-item-sliding>
        </TransitionGroup>
      </ion-list>

      <div v-else class="cook-empty">
        <ion-icon :icon="keyword ? ioniconsSearchOutline : ioniconsStarOutline" />
        <h3>{{ keyword ? '没有找到相关收藏' : '还没有收藏的菜谱' }}</h3>
        <p>{{ keyword ? '换个关键词试试' : '在「菜谱」里左滑或点星标即可收藏' }}</p>
      </div>
    </ion-content>
  </ion-page>
</template>

<style scoped>
.favorites-segment {
  --padding-bottom: 8px;
}
</style>
