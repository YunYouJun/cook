<script lang="ts" setup>
import type { RecipeItem } from '~/types'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { addRecipeHistory } from '~/composables/store/history'
import { getEmojisFromStuff } from '~/utils'
import { getRecipeLink } from '~/utils/recipe'

/**
 * 结果列表的一行：菜名 + 用到的食材（缺的食材标橙色），右侧是厨具；点击在新窗口打开做法
 */
const props = defineProps<{
  recipe: RecipeItem
  /** 还缺的食材，未传表示不做匹配（只按厨具筛选时） */
  missing?: string[]
}>()

const { proxy } = useScriptGoogleTagManager()

// 有的菜谱用中文逗号把多种食材记在一项里，先拆开、合并别名再展示
const stuff = computed(() => uniqueStuff(props.recipe.stuff))
const thumb = computed(() => props.recipe.tags?.includes('杂烩') ? '🍲' : getEmojisFromStuff(stuff.value)[0] ?? '🍽️')
const ingredients = computed(() => stuff.value.filter(name => !props.missing?.includes(name)).join(' · '))
const tool = computed(() => props.recipe.tools?.[0] === '一口大锅' ? '炒锅' : props.recipe.tools?.[0])

function onClick() {
  addRecipeHistory(props.recipe)
  proxy.dataLayer.push({ event: 'click', category: `dish_${props.recipe.name}`, action: 'click_recipe', label: '跳转菜谱' })
  proxy.dataLayer.push({ event: 'click_dish', action: props.recipe.name })
}
</script>

<template>
  <ion-item
    class="recipe-result-row"
    :button="true"
    :detail="false"
    :href="getRecipeLink(recipe)"
    target="_blank"
    rel="noopener"
    :aria-label="`${recipe.name}，在新窗口查看做法`"
    @click="onClick"
  >
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
    <span slot="end" class="recipe-result-meta">
      {{ tool }}
      <ion-icon :icon="ioniconsOpenOutline" aria-hidden="true" />
    </span>
  </ion-item>
</template>

<style scoped>
.recipe-result-row {
  --padding-start: 16px;
  --inner-padding-end: 16px;
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
.recipe-result-meta ion-icon {
  font-size: 13px;
  opacity: 0.6;
}
</style>
