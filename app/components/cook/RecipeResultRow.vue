<script lang="ts" setup>
import type { RecipeItem } from '~/types'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { getEmojisFromStuff } from '~/utils'

/**
 * 结果列表的一行：菜名 + 用到的食材（缺的食材标橙色），右侧是厨具；点击打开详情
 */
const props = defineProps<{
  recipe: RecipeItem
  /** 还缺的食材，未传表示不做匹配（只按厨具筛选时） */
  missing?: string[]
}>()

defineEmits<{ select: [] }>()

// 有的菜谱用中文逗号把多种食材记在一项里，先拆开、合并别名再展示
const stuff = computed(() => uniqueStuff(props.recipe.stuff))
const thumb = computed(() => props.recipe.tags?.includes('杂烩') ? '🍲' : getEmojisFromStuff(stuff.value)[0] ?? '🍽️')
const ingredients = computed(() => stuff.value.filter(name => !props.missing?.includes(name)).join(' · '))
const tool = computed(() => props.recipe.tools?.[0] === '一口大锅' ? '炒锅' : props.recipe.tools?.[0])
</script>

<template>
  <ion-item class="recipe-result-row" :button="true" :detail="true" @click="$emit('select')">
    <span slot="start" class="recipe-result-thumb" aria-hidden="true">{{ thumb }}</span>
    <ion-label>
      <h3>{{ recipe.name }}</h3>
      <p>
        <span v-if="missing?.length" class="recipe-result-missing">缺 {{ missing.join('、') }}</span>
        <template v-if="missing?.length && ingredients">
          ·
        </template>
        {{ ingredients }}
      </p>
    </ion-label>
    <span slot="end" class="recipe-result-meta">{{ tool }}</span>
  </ion-item>
</template>

<style scoped>
.recipe-result-row {
  --padding-start: 16px;
  --inner-padding-end: 12px;
  --min-height: 60px;
}
.recipe-result-thumb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  margin-inline-end: 12px;
  border-radius: 8px;
  background: var(--cook-fill);
  font-size: 20px;
}
.recipe-result-row h3 {
  margin: 0;
  font-size: 17px;
  line-height: 22px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.recipe-result-row p {
  margin: 2px 0 0;
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.recipe-result-missing {
  color: var(--cook-orange);
  font-weight: 500;
}
.recipe-result-meta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-inline-start: 8px;
  font-size: 13px;
  color: var(--ion-color-medium);
}
</style>
