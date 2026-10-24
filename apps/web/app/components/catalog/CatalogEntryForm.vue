<script setup lang="ts">
import type { CatalogCategory } from '@yunyoujun/cook/personal-catalog'
import { CATALOG_CATEGORIES } from '@yunyoujun/cook/personal-catalog'
import { catalogTitles } from '~/composables/usePersonalCatalog'

defineEmits<{ submit: [] }>()
const name = defineModel<string>('name', { required: true })
const category = defineModel<CatalogCategory>('category', { required: true })
</script>

<template>
  <form @submit.prevent="$emit('submit')">
    <ion-list :inset="true">
      <ion-item><ion-input v-model="name" label="名称" placeholder="例如：鹰嘴豆" :maxlength="30" /></ion-item>
      <ion-item>
        <ion-select v-model="category" label="类别" interface="popover">
          <ion-select-option v-for="group in CATALOG_CATEGORIES" :key="group" :value="group">
            {{ catalogTitles[group] }}
          </ion-select-option>
        </ion-select>
      </ion-item>
    </ion-list>
    <p class="cook-group-footer">
      添加后可在做菜和私人菜谱中选用。
    </p>
    <button type="submit" hidden>
      添加
    </button>
  </form>
</template>
