<script lang="ts" setup>
import type { DbRecipeItem } from '~/utils/db'
import { isFavorited, toggleFavorite } from '~/composables/store/favorite'
import { addRecipeHistory } from '~/composables/store/history'
import { openRecipe } from '~/utils/recipe'
import { yunleHaptic } from '~/utils/yunleHost'

/**
 * 菜谱列表的一行：收藏状态在行内自己读，父列表重新渲染时只要 dish 不变，这一行就不会跟着更新
 */
const props = defineProps<{
  dish: DbRecipeItem
}>()

const emit = defineEmits<{
  toggled: [favorited: boolean]
}>()

const sliding = ref<{ $el: HTMLIonItemSlidingElement }>()
const favorited = computed(() => isFavorited(props.dish))

function open() {
  addRecipeHistory(props.dish)
  openRecipe(props.dish)
}

function toggle() {
  toggleFavorite(props.dish)
  void yunleHaptic(favorited.value ? 'success' : 'light')
  void sliding.value?.$el.close()
  emit('toggled', favorited.value)
}
</script>

<template>
  <ion-item-sliding ref="sliding">
    <ion-item :button="true" :detail="false" @click="open">
      <ion-label class="truncate">
        <DishLabel class="text-sm" :dish="dish" />
      </ion-label>
      <ion-button
        slot="end"
        class="recipe-row-star"
        fill="clear"
        :aria-label="favorited ? '取消收藏' : '收藏'"
        :aria-pressed="favorited"
        @click.stop="toggle()"
      >
        <ion-icon slot="icon-only" :icon="favorited ? ioniconsStar : ioniconsStarOutline" color="warning" />
      </ion-button>
    </ion-item>

    <ion-item-options side="end" @ion-swipe="toggle">
      <ion-item-option color="warning" :expandable="true" @click="toggle">
        {{ favorited ? '取消收藏' : '收藏' }}
      </ion-item-option>
    </ion-item-options>
  </ion-item-sliding>
</template>

<style scoped>
.recipe-row-star ion-icon {
  transition: transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
}
.recipe-row-star[aria-pressed='true'] ion-icon {
  animation: recipe-row-star-pop 0.36s cubic-bezier(0.34, 1.56, 0.64, 1);
}
@keyframes recipe-row-star-pop {
  0% {
    transform: scale(0.6);
  }
  60% {
    transform: scale(1.2);
  }
  100% {
    transform: scale(1);
  }
}
@media (prefers-reduced-motion: reduce) {
  .recipe-row-star[aria-pressed='true'] ion-icon {
    animation: none;
  }
}
</style>
