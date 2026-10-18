<script lang="ts" setup>
import type { RecipeMatch } from '@yunyoujun/cook'
import type { RecipeItem } from '~/types'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { isFavorited, toggleFavorite } from '~/composables/store/favorite'
import { addRecipeHistory } from '~/composables/store/history'
import { getEmojisFromStuff } from '~/utils'
import { getRecipeFeedbackLink, openRecipe } from '~/utils/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

/**
 * 菜品详情：已有 / 缺少 / 常备分开列出，可以收藏或去 B 站看做法
 */
const props = defineProps<{
  recipe?: RecipeItem
  match?: RecipeMatch
}>()
const open = defineModel<boolean>('open', { required: true })

const { proxy } = useScriptGoogleTagManager()

const stuff = computed(() => props.recipe ? uniqueStuff(props.recipe.stuff) : [])
const thumb = computed(() => props.recipe?.tags?.includes('杂烩') ? '🍲' : getEmojisFromStuff(stuff.value)[0] ?? '🍽️')
const meta = computed(() => {
  if (!props.recipe)
    return ''
  const tools = props.recipe.tools?.map(tool => tool === '一口大锅' ? '炒锅' : tool).join(' / ') || '厨具未记录'
  return props.recipe.difficulty ? `${tools} · ${props.recipe.difficulty}` : tools
})
const groups = computed(() => {
  const match = props.match
  if (!match)
    return [{ label: '食材', items: stuff.value, tone: 'neutral' }]
  return [
    { label: '已有', items: match.matchedStuff, tone: 'have' },
    { label: '还缺', items: match.missingStuff, tone: 'missing' },
    { label: '常备调料', items: match.pantryStuff, tone: 'neutral' },
  ].filter(group => group.items.length)
})

const canFavorite = computed(() => typeof (props.recipe as { id?: number } | undefined)?.id === 'number')
const favorited = computed(() => props.recipe ? isFavorited(props.recipe) : false)

function onFavorite() {
  if (!props.recipe)
    return
  toggleFavorite(props.recipe)
  void yunleHaptic(favorited.value ? 'success' : 'light')
}

function watchVideo() {
  if (!props.recipe)
    return
  addRecipeHistory(props.recipe)
  proxy.dataLayer.push({ event: 'click', category: `dish_${props.recipe.name}`, action: 'click_recipe', label: '跳转菜谱' })
  proxy.dataLayer.push({ event: 'click_dish', action: props.recipe.name })
  openRecipe(props.recipe)
}
</script>

<template>
  <ion-modal :is-open="open" :breakpoints="[0, 0.62, 1]" :initial-breakpoint="0.62" @did-dismiss="open = false">
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="end">
          <ion-button :strong="true" @click="open = false">
            完成
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content v-if="recipe">
      <div class="recipe-detail-hero">
        <span class="recipe-detail-thumb" aria-hidden="true">{{ thumb }}</span>
        <h2>{{ recipe.name }}</h2>
        <p>{{ meta }}</p>
      </div>

      <div class="recipe-detail-actions">
        <ion-button expand="block" class="recipe-detail-primary" @click="watchVideo">
          <ion-icon slot="start" :icon="ioniconsPlayCircle" />
          去 B 站看做法
        </ion-button>
        <ion-button v-if="canFavorite" fill="clear" class="recipe-detail-fav" :aria-pressed="favorited" :aria-label="favorited ? '取消收藏' : '收藏'" @click="onFavorite">
          <ion-icon slot="icon-only" :icon="favorited ? ioniconsStar : ioniconsStarOutline" color="warning" />
        </ion-button>
      </div>

      <section v-for="group in groups" :key="group.label" :aria-label="group.label">
        <h3 class="cook-group-header">
          {{ group.label }} · {{ group.items.length }} 种
        </h3>
        <div class="recipe-detail-chips">
          <span v-for="name in group.items" :key="name" class="recipe-detail-chip" :class="`is-${group.tone}`">{{ name }}</span>
        </div>
      </section>

      <p class="cook-group-footer recipe-detail-note">
        食材来自菜谱记录，用量和调味以视频为准。
        <a :href="getRecipeFeedbackLink(recipe)" target="_blank" rel="noreferrer">视频失效或信息不对？去反馈</a>
      </p>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.recipe-detail-hero {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 24px 0;
  text-align: center;
}
.recipe-detail-thumb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 72px;
  height: 72px;
  border-radius: 18px;
  background: var(--ion-item-background, #fff);
  font-size: 40px;
  animation: recipe-detail-pop 0.42s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.recipe-detail-hero h2 {
  margin: 12px 0 4px;
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
}
.recipe-detail-hero p {
  margin: 0;
  font-size: 15px;
  color: var(--ion-color-medium);
}
.recipe-detail-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 20px 16px 4px;
}
.recipe-detail-primary {
  flex: 1;
  margin: 0;
  --border-radius: 12px;
  min-height: 50px;
  font-size: 17px;
  font-weight: 600;
}
.recipe-detail-fav {
  flex: none;
  width: 50px;
  height: 50px;
  margin: 0;
  --border-radius: 12px;
  --background: var(--ion-item-background, #fff);
  font-size: 22px;
}
.recipe-detail-fav[aria-pressed='true'] ion-icon {
  animation: recipe-detail-pop 0.36s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.recipe-detail-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 0 16px;
  padding: 12px;
  border-radius: 10px;
  background: var(--ion-item-background, #fff);
}
.recipe-detail-chip {
  display: inline-flex;
  align-items: center;
  height: 30px;
  padding: 0 12px;
  border-radius: 15px;
  font-size: 15px;
}
.recipe-detail-chip.is-have {
  background: rgba(var(--ion-color-primary-rgb), 0.14);
  color: var(--ion-color-primary);
  font-weight: 500;
}
.recipe-detail-chip.is-missing {
  background: color-mix(in srgb, var(--cook-orange) 14%, transparent);
  color: var(--cook-orange);
  font-weight: 500;
}
.recipe-detail-chip.is-neutral {
  background: var(--cook-fill);
}
.recipe-detail-note {
  margin-top: 8px;
  padding-bottom: 32px;
}
.recipe-detail-note a {
  display: block;
  margin-top: 4px;
  color: var(--ion-color-primary);
}
@keyframes recipe-detail-pop {
  0% {
    transform: scale(0.7);
  }
  60% {
    transform: scale(1.08);
  }
  100% {
    transform: scale(1);
  }
}
@media (prefers-reduced-motion: reduce) {
  .recipe-detail-thumb,
  .recipe-detail-fav[aria-pressed='true'] ion-icon {
    animation: none;
  }
}
</style>
