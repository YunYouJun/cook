<script setup lang="ts">
import type { CatalogCategory, CatalogEntry, PersonalCatalog } from '@yunyoujun/cook/personal-catalog'
import { CATALOG_CATEGORIES, catalogKey } from '@yunyoujun/cook/personal-catalog'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { shallowRef } from 'vue'
import { catalogTitles } from '~/composables/usePersonalCatalog'
import * as base from '~/data/food'

const catalog = usePersonalCatalog()
const name = shallowRef('')
const category = shallowRef<CatalogCategory>('vegetable')
const error = shallowRef('')
function change(update: (value: PersonalCatalog) => PersonalCatalog) {
  try {
    catalog.update(update)
    error.value = ''
    return true
  }
  catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败，原有配置未改变，请重试。'
    return false
  }
}
function add() {
  const entered = name.value.trim()
  const entry: CatalogEntry = { category: category.value, name: category.value === 'tools' ? entered : (uniqueStuff([entered])[0] ?? '') }
  if (change((current) => {
    const all = [...current.items, ...CATALOG_CATEGORIES.flatMap(group => base[group].map(item => ({ category: group, name: item.name })))]
    if (all.some(item => catalogKey(item) === catalogKey(entry)))
      throw new Error('这个名称已经存在；隐藏的内置条目可在下方恢复。')
    return { ...current, items: [...current.items, entry] }
  })) {
    name.value = ''
  }
}
function hidden(group: CatalogCategory, title: string) {
  return catalog.value.value.hidden.some(item => item.category === group && item.name === title)
}
function toggleHidden(group: CatalogCategory, title: string) {
  change(current => ({ ...current, hidden: current.hidden.some(item => item.category === group && item.name === title)
    ? current.hidden.filter(item => item.category !== group || item.name !== title)
    : [...current.hidden, { category: group, name: title }] }))
}
function remove(entry: CatalogEntry) {
  change(current => ({ ...current, items: current.items.filter(item => catalogKey(item) !== catalogKey(entry)) }))
}
</script>

<template>
  <p class="cook-group-footer">
    添加自己的食材和厨具，或隐藏不常用的内置条目。隐藏和删除只影响选择列表，已有菜谱、冰箱和忌口保留。
  </p>
  <p v-if="catalog.error.value" role="alert" class="cook-group-footer">
    {{ catalog.error.value }} <ion-button fill="clear" @click="catalog.refresh">
      重试读取
    </ion-button>
  </p>
  <form @submit.prevent="add">
    <ion-list :inset="true">
      <ion-item>
        <ion-select v-model="category" label="类别" interface="popover">
          <ion-select-option v-for="group in CATALOG_CATEGORIES" :key="group" :value="group">
            {{ catalogTitles[group] }}
          </ion-select-option>
        </ion-select>
      </ion-item>
      <ion-item><ion-input v-model="name" label="名称" placeholder="例如：鹰嘴豆" :maxlength="30" /></ion-item>
    </ion-list>
    <ion-button type="submit" expand="block" class="ion-margin" :disabled="!name.trim() || !!catalog.error.value">
      添加条目
    </ion-button>
  </form>
  <p v-if="error" role="alert" class="cook-group-footer">
    {{ error }}
  </p>
  <template v-for="group in CATALOG_CATEGORIES" :key="group">
    <h2 class="cook-group-header">
      {{ catalogTitles[group] }}
    </h2>
    <ion-list :inset="true">
      <ion-item v-for="entry in catalog.value.value.items.filter(item => item.category === group)" :key="entry.name">
        <ion-label>{{ entry.name }} <ion-note>自定义</ion-note></ion-label>
        <ion-button slot="end" fill="clear" color="danger" :aria-label="`删除${entry.name}`" @click="remove(entry)">
          删除
        </ion-button>
      </ion-item>
      <ion-item v-for="entry in base[group]" :key="`base:${entry.name}`">
        <ion-label>{{ entry.emoji }} {{ entry.label || entry.name }}</ion-label>
        <ion-button slot="end" fill="clear" :aria-label="`${hidden(group, entry.name) ? '恢复' : '隐藏'}${entry.name}`" @click="toggleHidden(group, entry.name)">
          {{ hidden(group, entry.name) ? '恢复' : '隐藏' }}
        </ion-button>
      </ion-item>
    </ion-list>
  </template>
  <CatalogBackup />
</template>
