<script lang="ts" setup>
import type { IonSearchbarCustomEvent, SearchbarInputEventDetail } from '@ionic/core'
import type { DbRecipeItem } from '~/utils/db'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useIndexedDB } from '~/composables/db'
import { favoriteRecipeIds } from '~/composables/store/favorite'
import { db } from '~/utils/db'
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  alias: ['/library'],
})

const keyword = ref('')
const loading = ref(true)
const recipes = shallowRef<DbRecipeItem[]>([])
const view = ref<'all' | 'fav'>('all')

const favoriteIdSet = computed(() => new Set(favoriteRecipeIds.value))
const isFav = (r: DbRecipeItem) => r.id != null && favoriteIdSet.value.has(r.id)

const filtered = computed(() => {
  const text = keyword.value.trim()
  return text ? recipes.value.filter(r => r.name.includes(text)) : recipes.value
})
const displayed = computed(() => view.value === 'fav' ? filtered.value.filter(isFav) : filtered.value)
const favCount = computed(() => recipes.value.filter(isFav).length)

const showToast = ref(false)
const toastMessage = ref('')

onMounted(async () => {
  try {
    await useIndexedDB().init()
    recipes.value = await db.recipes.toArray()
  }
  finally {
    loading.value = false
  }
})

function onViewChange(value: unknown) {
  view.value = value === 'fav' ? 'fav' : 'all'
  void yunleHaptic('light')
}

function onInput(ev: IonSearchbarCustomEvent<SearchbarInputEventDetail>) {
  keyword.value = ev.detail.value ?? ''
}

function onToggled(favorited: boolean) {
  toastMessage.value = favorited ? '已添加到收藏' : '已从收藏移除'
  showToast.value = true
}

// 先渲染首屏的行，其余在空闲时一次补齐（分成很多小批反而因为反复 diff 整个列表更慢）。
// 不做虚拟滚动：行全部留在 DOM 里，滚动时不需要任何 JS 和重新布局；
// 每行是独立组件，页面重新渲染（例如切换 tab）时行不会跟着更新。
const FIRST_BATCH = 30
const renderCount = ref(FIRST_BATCH)
const rendered = computed(() => displayed.value.slice(0, renderCount.value))

// 每 50 行一组，组外用 content-visibility 跳过布局：切回这个 tab 时不用重新布局整张列表，
// 滚动时也只在进入新的一组时布局一次，而不是每一帧都布局
const CHUNK_SIZE = 50
const chunks = computed(() => {
  const list = rendered.value
  const result: DbRecipeItem[][] = []
  for (let i = 0; i < list.length; i += CHUNK_SIZE)
    result.push(list.slice(i, i + CHUNK_SIZE))
  return result
})

// iOS Safari 没有 requestIdleCallback
function onIdle(cb: () => void) {
  return typeof requestIdleCallback === 'function'
    ? requestIdleCallback(cb, { timeout: 300 })
    : window.setTimeout(cb, 32)
}
function cancelIdle(handle: number) {
  return typeof cancelIdleCallback === 'function'
    ? cancelIdleCallback(handle)
    : window.clearTimeout(handle)
}

let idleHandle = 0
watch(displayed, (list) => {
  cancelIdle(idleHandle)
  renderCount.value = FIRST_BATCH
  if (list.length > FIRST_BATCH)
    idleHandle = onIdle(() => (renderCount.value = list.length))
})
onBeforeUnmount(() => cancelIdle(idleHandle))
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-title>菜谱</ion-title>
        <ion-buttons slot="end">
          <ion-button title="添加菜谱" router-link="/recipes/new">
            <ion-icon slot="icon-only" :icon="ioniconsAddCircleOutline" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true">
      <ion-header collapse="condense">
        <ion-toolbar>
          <ion-title size="large">
            菜谱
          </ion-title>
        </ion-toolbar>
        <ion-toolbar>
          <ion-searchbar
            placeholder="搜索菜谱"
            :debounce="150"
            show-clear-button="focus"
            @ion-input="onInput"
          />
        </ion-toolbar>
        <ion-toolbar class="library-segment">
          <ion-segment
            :value="view"
            @ion-change="onViewChange($event.detail.value)"
          >
            <ion-segment-button value="all">
              <ion-label>全部</ion-label>
            </ion-segment-button>
            <ion-segment-button value="fav">
              <ion-label>收藏 ({{ favCount }})</ion-label>
            </ion-segment-button>
          </ion-segment>
        </ion-toolbar>
      </ion-header>

      <ion-list v-if="loading" aria-busy="true" aria-label="正在加载菜谱">
        <ion-item v-for="i in 12" :key="i">
          <ion-label>
            <ion-skeleton-text :animated="true" :style="{ width: `${40 + (i * 37) % 45}%` }" />
          </ion-label>
        </ion-item>
      </ion-list>

      <ion-list v-else-if="displayed.length">
        <div
          v-for="(chunk, i) in chunks"
          :key="i"
          class="library-chunk"
          :style="{ containIntrinsicSize: `auto ${chunk.length * 44}px` }"
        >
          <RecipeListRow
            v-for="dish in chunk"
            :key="dish.id ?? dish.name"
            class="library-row"
            :dish="dish"
            @toggled="onToggled"
          />
        </div>
      </ion-list>

      <div v-else class="cook-empty">
        <ion-icon :icon="view === 'fav' && !keyword ? ioniconsStarOutline : ioniconsSearchOutline" />
        <h3>{{ keyword ? '没有找到相关菜谱' : '还没有收藏的菜谱' }}</h3>
        <p>{{ keyword ? '换个关键词试试' : '左滑菜谱或点星标即可收藏' }}</p>
      </div>
    </ion-content>
    <ion-toast
      :is-open="showToast"
      :message="toastMessage"
      duration="1200"
      position="top"
      @did-dismiss="showToast = false"
    />
  </ion-page>
</template>

<style scoped>
.library-segment {
  --padding-bottom: 8px;
}
.library-chunk {
  content-visibility: auto;
}
.library-row {
  height: 44px;
}
</style>
