<script lang="ts" setup>
import type { RecipeMatch } from '@yunyoujun/cook'
import type { RecipeItem } from '~/types'
import { actionSheetController, alertController, toastController } from '@ionic/vue'
import { uniqueStuff } from '@yunyoujun/cook/recommend'
import { isFavorited, toggleFavorite } from '~/composables/store/favorite'
import { addRecipeHistory } from '~/composables/store/history'
import { getEmojisFromStuff } from '~/utils'
import { trackCookEvent } from '~/utils/analytics'
import { isCustomRecipe } from '~/utils/customRecipe'
import { promptText } from '~/utils/dialog'
import { buildFeedbackMail, feedbackKinds } from '~/utils/feedback'
import { getRecipeFeedbackLink, getRecipeLink, getRecipeTextLink, openRecipe } from '~/utils/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

/**
 * 菜品详情：已有 / 缺少 / 常备分开列出，可以收藏或去 B 站看做法
 */
const props = defineProps<{
  recipe?: RecipeItem
  match?: RecipeMatch
}>()
const open = defineModel<boolean>('open', { required: true })

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
    { label: '还能加', items: match.optionalStuff, tone: 'neutral' },
  ].filter(group => group.items.length)
})

const custom = computed(() => props.recipe && isCustomRecipe(props.recipe) ? props.recipe : undefined)
const link = computed(() => props.recipe ? getRecipeLink(props.recipe) : '')
const textLink = computed(() => props.recipe ? getRecipeTextLink(props.recipe) : '')
const linkLabel = computed(() => {
  if (props.recipe?.bv || link.value.includes('bilibili.com'))
    return '去 B 站看做法'
  // 收录的菜没有视频时，链接就是图文做法
  return custom.value ? '看做法' : '看图文做法'
})

const canFavorite = computed(() => typeof (props.recipe as { id?: number } | undefined)?.id === 'number')
const favorited = computed(() => props.recipe ? isFavorited(props.recipe) : false)

function onFavorite() {
  if (!props.recipe)
    return
  toggleFavorite(props.recipe)
  void yunleHaptic(favorited.value ? 'success' : 'light')
}

const feedback = useRecipeFeedback()

/** 选择问题类型后直接提交；提交不了时引导发邮件或去 GitHub 填表（菜名和视频已预填） */
async function onFeedback() {
  const recipe = props.recipe
  if (!recipe)
    return
  if (typeof recipe.id !== 'number') {
    window.open(getRecipeFeedbackLink(recipe), '_blank')
    return
  }

  const sheet = await actionSheetController.create({
    header: '这道菜有什么问题？',
    buttons: [
      ...feedbackKinds.map(item => ({ text: item.label, data: item.kind })),
      { text: '取消', role: 'cancel' },
    ],
  })
  await sheet.present()
  const { data: kind, role } = await sheet.onDidDismiss()
  if (role === 'cancel' || !kind)
    return

  let note = ''
  if (kind === 'other') {
    const text = await promptText({ header: '具体是什么问题？', placeholder: '比如：用量不对、步骤有误', okText: '提交' })
    if (text === undefined)
      return
    note = text
  }

  try {
    await feedback.report({ recipeId: recipe.id, recipeName: recipe.name, kind, note })
    void yunleHaptic('success')
    const toast = await toastController.create({ message: '收到了，谢谢反馈', duration: 1800, position: 'top' })
    await toast.present()
  }
  catch {
    const mail = buildFeedbackMail({
      topic: recipe.name,
      lines: [
        `菜谱：${recipe.name}（#${recipe.id}）`,
        `问题：${feedbackKinds.find(item => item.kind === kind)?.label ?? kind}`,
        note,
      ],
    })
    const alert = await alertController.create({
      header: '暂时没能提交',
      message: '可以发邮件给我们，或者到 GitHub 填写反馈表单，菜名和视频都已经帮你填好。',
      buttons: [
        { text: '取消', role: 'cancel' },
        { text: '去 GitHub', handler: () => { window.open(getRecipeFeedbackLink(recipe), '_blank') } },
        { text: '发邮件', handler: () => { window.location.href = mail } },
      ],
    })
    await alert.present()
  }
}

function watchVideo() {
  if (!props.recipe)
    return
  addRecipeHistory(props.recipe)
  trackCookEvent('click_dish')
  openRecipe(props.recipe)
}

function readText() {
  if (!props.recipe || !textLink.value)
    return
  addRecipeHistory(props.recipe)
  trackCookEvent('click_dish')
  window.open(textLink.value, '_blank')
}

function onPresented() {
  if (open.value && props.recipe)
    trackCookEvent('view_recipe_detail')
}
</script>

<template>
  <ion-modal :is-open="open" :breakpoints="[0, 0.62, 1]" :initial-breakpoint="0.62" @did-present="onPresented" @did-dismiss="open = false">
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
        <ion-button v-if="link" expand="block" class="recipe-detail-primary" @click="watchVideo">
          <ion-icon slot="start" :icon="linkLabel === '看图文做法' ? ioniconsDocumentTextOutline : ioniconsPlayCircle" />
          {{ linkLabel }}
        </ion-button>
        <ion-button v-if="textLink" fill="clear" class="recipe-detail-fav" aria-label="看图文做法" title="看图文做法" @click="readText">
          <ion-icon slot="icon-only" :icon="ioniconsDocumentTextOutline" />
        </ion-button>
        <ion-button
          v-if="custom"
          :expand="link ? undefined : 'block'"
          :fill="link ? 'clear' : 'solid'"
          :class="link ? 'recipe-detail-fav' : 'recipe-detail-primary'"
          :router-link="`/recipes/new?id=${custom.customId}`"
          aria-label="编辑菜谱"
          @click="open = false"
        >
          <ion-icon :slot="link ? 'icon-only' : 'start'" :icon="ioniconsCreateOutline" />
          <template v-if="!link">
            编辑菜谱
          </template>
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

      <section v-if="custom?.note" aria-label="做法备注">
        <h3 class="cook-group-header">
          做法备注
        </h3>
        <p class="recipe-detail-text">
          {{ custom.note }}
        </p>
      </section>

      <p class="cook-group-footer recipe-detail-note">
        <template v-if="custom">
          这是你自己添加的菜谱，只保存在这台设备上。
        </template>
        <template v-else>
          食材来自菜谱记录，用量和调味以{{ recipe.bv ? '视频' : '做法原文' }}为准。
          <button type="button" class="recipe-detail-feedback" @click="onFeedback">
            {{ recipe.bv ? '视频' : '链接' }}打不开或信息不对？反馈一下
          </button>
        </template>
      </p>
    </ion-content>
  </ion-modal>
</template>

<style scoped>
.recipe-detail-text {
  margin: 0 16px;
  padding: 12px 16px;
  border-radius: 10px;
  background: var(--ion-item-background, #fff);
  font-size: 16px;
  line-height: 22px;
  white-space: pre-wrap;
}
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
.recipe-detail-feedback {
  display: block;
  margin: 4px auto 0;
  padding: 4px 8px;
  border: 0;
  background: none;
  color: var(--ion-color-primary);
  font: inherit;
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
