<script lang="ts" setup>
import type { IonSearchbarCustomEvent, SearchbarInputEventDetail } from '@ionic/core'
import type { DbRecipeItem } from '~/utils/db'
import { watchDebounced } from '@vueuse/core'
import { computed, onMounted, ref } from 'vue'
import { useIndexedDB } from '~/composables/db'
import { isFavorited, toggleFavorite } from '~/composables/store/favorite'
import { recipeHistories } from '~/composables/store/history'
import { db } from '~/utils/db'

definePageMeta({
  alias: ['/library'],
})

const keyword = ref('')
const loading = ref(false)
const recipes = ref<DbRecipeItem[]>([])
const view = ref<'all' | 'fav'>('all')

const displayed = computed(() => {
  return view.value === 'fav' ? recipes.value.filter(r => isFavorited(r)) : recipes.value
})

const favCount = computed(() => recipes.value.filter(r => isFavorited(r)).length)

const showToast = ref(false)
const toastMessage = ref('')

async function loadAll() {
  loading.value = true
  try {
    recipes.value = await db.recipes.toArray()
  }
  finally {
    loading.value = false
  }
}

async function runSearch(q: string) {
  const text = (q || '').trim()
  if (!text)
    return loadAll()
  loading.value = true
  try {
    recipes.value = await db.recipes
      .filter(r => r.name.includes(text))
      .toArray()
  }
  finally {
    loading.value = false
  }
}

onMounted(async () => {
  // ensure IndexedDB has data
  const { init } = useIndexedDB()
  await init()
  await loadAll()
})

watchDebounced(keyword, (q) => {
  runSearch(q)
}, { debounce: 200, maxWait: 500 })

function onInput(ev: IonSearchbarCustomEvent<SearchbarInputEventDetail>) {
  // Ionic emits detail.value
  keyword.value = ev?.detail?.value ?? ''
}

function onClear() {
  keyword.value = ''
}

function openDishLink(dish: DbRecipeItem) {
  // keep history like DishTag did
  recipeHistories.value.push({ recipe: dish, time: Date.now() })
  const href = dish.link || `https://www.bilibili.com/video/${dish.bv}`
  window.open(href, '_blank')
}

function onToggleFavorite(item: DbRecipeItem) {
  toggleFavorite(item)
  toastMessage.value = isFavorited(item) ? '已添加到收藏' : '已从收藏移除'
  showToast.value = true
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>菜谱列表</ion-title>

        <ion-buttons slot="end">
          <ion-button title="添加菜谱" router-link="/recipes/new">
            <ion-icon slot="icon-only" :icon="ioniconsAddCircleOutline" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>

      <ion-toolbar>
        <ion-searchbar
          animated
          placeholder="搜索菜谱"
          :debounce="300"
          show-clear-button="focus"
          @ion-input="onInput"
          @ion-clear="onClear"
        />
      </ion-toolbar>

      <ion-toolbar class="pb-1.5 -mt-2">
        <ion-segment
          :value="view"
          @ion-change="e => (view = (e.detail.value as 'all' | 'fav') ?? 'all')"
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

    <ion-content>
      <div v-if="loading" class="ion-padding text-center">
        <ion-spinner name="crescent" />
      </div>

      <ion-list v-else-if="displayed.length">
        <ion-item-sliding v-for="item in displayed" :key="item.id ?? item.name">
          <ion-item @click="openDishLink(item)">
            <ion-label class="truncate">
              <DishLabel class="text-sm" :dish="item" />
            </ion-label>
            <ion-button slot="end" fill="clear" @click.stop="onToggleFavorite(item)">
              <ion-icon :icon="isFavorited(item) ? ioniconsStar : ioniconsStarOutline" color="warning" />
            </ion-button>
          </ion-item>

          <ion-item-options>
            <ion-item-option color="warning" @click="onToggleFavorite(item)">
              {{ isFavorited(item) ? '取消收藏' : '添加收藏' }}
            </ion-item-option>
          </ion-item-options>
        </ion-item-sliding>
      </ion-list>

      <div
        v-else-if="(keyword || view === 'fav') && displayed.length === 0"
        class="ion-padding text-center"
      >
        <ion-note>没有找到相关菜谱</ion-note>
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
