<script setup lang="ts">
import type { CatalogCategory, CatalogEntry, PersonalCatalog } from '@yunyoujun/cook/personal-catalog'
import { CATALOG_CATEGORIES, catalogKey } from '@yunyoujun/cook/personal-catalog'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { computed, shallowRef } from 'vue'
import { catalogTitles } from '~/composables/usePersonalCatalog'
import * as base from '~/data/food'

const catalog = usePersonalCatalog()
const name = shallowRef('')
const category = shallowRef<CatalogCategory>('vegetable')
const error = shallowRef('')
const activeCategory = shallowRef<CatalogCategory>('vegetable')
const query = shallowRef('')
const adding = shallowRef(false)
const customEntries = computed(() => catalog.value.value.items.filter(item => item.category === activeCategory.value && item.name.includes(query.value.trim())))
const builtinEntries = computed(() => base[activeCategory.value].filter(item => item.name.includes(query.value.trim()) || item.label?.includes(query.value.trim())))
function startAdding() {
  category.value = activeCategory.value
  adding.value = true
}
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
    activeCategory.value = category.value
    adding.value = false
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
  <div class="catalog-controls">
    <ion-segment v-model="activeCategory" aria-label="管理分类">
      <ion-segment-button v-for="group in CATALOG_CATEGORIES" :key="group" :value="group">
        <ion-label>{{ catalogTitles[group] }}</ion-label>
      </ion-segment-button>
    </ion-segment>
    <ion-searchbar v-model="query" placeholder="搜索当前分类" />
    <ion-button fill="clear" @click="startAdding">
      添加条目
    </ion-button>
  </div>
  <p class="cook-group-footer">
    隐藏只影响选择列表，已有菜谱、冰箱和忌口保留。
  </p>
  <p v-if="catalog.error.value" role="alert" class="cook-group-footer">
    {{ catalog.error.value }} <ion-button fill="clear" @click="catalog.refresh">
      重试读取
    </ion-button>
  </p>
  <ion-modal :is-open="adding" @did-dismiss="adding = false">
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-button @click="adding = false">
            取消
          </ion-button>
        </ion-buttons>
        <ion-title>添加条目</ion-title>
        <ion-buttons slot="end">
          <ion-button :disabled="!name.trim() || !!catalog.error.value" @click="add">
            添加
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <CatalogEntryForm v-model:name="name" v-model:category="category" @submit="add" />
      <p v-if="error" role="alert" class="cook-group-footer">
        {{ error }}
      </p>
    </ion-content>
  </ion-modal>
  <p v-if="error" role="alert" class="cook-group-footer">
    {{ error }}
  </p>
  <section :aria-label="catalogTitles[activeCategory]">
    <h2 class="cook-group-header">
      {{ catalogTitles[activeCategory] }}
    </h2>
    <ion-list :inset="true">
      <ion-item v-for="entry in customEntries" :key="entry.name">
        <ion-label>{{ entry.name }} <ion-note>自定义</ion-note></ion-label>
        <ion-button slot="end" fill="clear" color="danger" :aria-label="`删除${entry.name}`" @click="remove(entry)">
          删除
        </ion-button>
      </ion-item>
      <ion-item v-for="entry in builtinEntries" :key="`base:${entry.name}`">
        <ion-label>{{ entry.emoji }} {{ entry.label || entry.name }}</ion-label>
        <ion-button slot="end" fill="clear" :aria-label="`${hidden(activeCategory, entry.name) ? '恢复' : '隐藏'}${entry.name}`" @click="toggleHidden(activeCategory, entry.name)">
          {{ hidden(activeCategory, entry.name) ? '恢复' : '隐藏' }}
        </ion-button>
      </ion-item>
    </ion-list>
    <p v-if="!customEntries.length && !builtinEntries.length" class="cook-group-footer">
      没有找到「{{ query }}」。
    </p>
  </section>
  <details class="catalog-backup">
    <summary>备份与迁移</summary>
    <CatalogBackup />
  </details>
</template>

<style scoped>
.catalog-controls {
  margin: 16px;
}
.catalog-controls ion-searchbar {
  padding-inline: 0;
}
.catalog-backup {
  margin: 24px 16px;
  border-radius: 12px;
  background: var(--ion-item-background, #fff);
}
.catalog-backup summary {
  min-height: 48px;
  padding: 14px 20px;
  cursor: pointer;
  font-size: 17px;
}
.catalog-backup summary:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 2px;
}
</style>
