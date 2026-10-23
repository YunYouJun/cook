<script setup lang="ts">
import type { LocalRecipeVideo } from '~/utils/localRecipeVideo'
import { liveQuery } from 'dexie'
import { onMounted, onUnmounted, shallowRef } from 'vue'
import { localVideoDB } from '~/utils/localRecipeVideo'

const items = shallowRef<LocalRecipeVideo[]>([])
const error = shallowRef('')
const selected = shallowRef<string>()
let subscription: { unsubscribe: () => void } | undefined
function load() {
  subscription?.unsubscribe()
  error.value = ''
  subscription = liveQuery(() => localVideoDB.videos.toArray()).subscribe({ next: value => items.value = value, error: () => error.value = '无法读取本机视频，请重试。' })
}
onMounted(load)
onUnmounted(() => subscription?.unsubscribe())
</script>

<template>
  <p class="cook-group-footer">
    这里保留所有本机视频，包括已删除菜谱的附件，可随时导出或移除。
  </p>
  <p v-if="error" role="alert">
    {{ error }} <ion-button @click="load">
      重试
    </ion-button>
  </p>
  <ion-list v-else :inset="true">
    <ion-item v-if="!items.length">
      <ion-label>还没有本机视频</ion-label>
    </ion-item>
    <template v-for="item in items" :key="item.customId">
      <ion-item :button="true" @click="selected = selected === item.customId ? undefined : item.customId">
        <ion-label>{{ item.recipeName }}<p>{{ item.filename }}</p></ion-label>
      </ion-item>
      <LocalRecipeVideo v-if="selected === item.customId" :custom-id="item.customId" :recipe-name="item.recipeName" editable />
    </template>
  </ion-list>
</template>
