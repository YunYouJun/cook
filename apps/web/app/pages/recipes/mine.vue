<script lang="ts" setup>
import type { CustomRecipe } from '~/composables/store/customRecipe'
import { customRecipes, removeCustomRecipe } from '~/composables/store/customRecipe'
import { showAlert } from '~/utils/dialog'
import { getRecipeLink, openRecipe } from '~/utils/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

definePageMeta({
  layout: 'child',
  title: '我的菜谱',
})

/**
 * 有来源时直接打开；详情入口保留备注和编辑。
 */
const detail = shallowRef<CustomRecipe>()
const detailOpen = ref(false)
const sorted = computed(() => [...customRecipes.value].sort((a, b) => b.updatedAt - a.updatedAt))

function showDetails(recipe: CustomRecipe) {
  detail.value = recipe
  detailOpen.value = true
}
function open(recipe: CustomRecipe) {
  if (getRecipeLink(recipe))
    openRecipe(recipe)
  else
    showDetails(recipe)
}

async function remove(recipe: CustomRecipe) {
  try {
    removeCustomRecipe(recipe.customId)
    void yunleHaptic('medium')
  }
  catch {
    await showAlert('删除失败', '没能保存更改，原有菜谱未改变。')
  }
}
</script>

<template>
  <ion-page>
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>我的菜谱</ion-title>
        <ion-buttons slot="end">
          <ion-button title="添加菜谱" router-link="/recipes/new">
            <ion-icon slot="icon-only" :icon="ioniconsAddCircleOutline" />
          </ion-button>
        </ion-buttons>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true">
      <PrivateRecipeBackup />
      <ion-list v-if="sorted.length">
        <TransitionGroup name="cook-row" tag="div" class="cook-row-group">
          <ion-item-sliding v-for="recipe in sorted" :key="recipe.customId">
            <ion-item :button="true" @click="open(recipe)">
              <ion-label>
                <h3>{{ recipe.name }}</h3>
                <p>{{ recipe.stuff.join(' · ') }}</p>
              </ion-label>
              <ion-button slot="end" fill="clear" :aria-label="`查看${recipe.name}详情`" @click.stop="showDetails(recipe)">
                详情
              </ion-button>
            </ion-item>

            <ion-item-options side="end" @ion-swipe="remove(recipe)">
              <ion-item-option color="danger" :expandable="true" @click="remove(recipe)">
                删除
              </ion-item-option>
            </ion-item-options>
          </ion-item-sliding>
        </TransitionGroup>
      </ion-list>

      <div v-else class="cook-empty">
        <ion-icon :icon="ioniconsBookOutline" />
        <h3>还没有自己的菜谱</h3>
        <p>添加家里常做的菜，搭配推荐和「吃什么」都会算上它</p>
        <ion-button fill="clear" router-link="/recipes/new">
          添加菜谱
        </ion-button>
      </div>
      <RecipeDetailSheet v-model:open="detailOpen" :recipe="detail" />
    </ion-content>
  </ion-page>
</template>
