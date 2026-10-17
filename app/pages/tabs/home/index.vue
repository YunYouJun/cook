<script lang="ts" setup>
definePageMeta({
  alias: ['/', '/home', '/tabs'],
})

useHead({
  title: '食用手册',
})

const ionContentRef = ref<{ $el: { scrollToTop: () => Promise<void> } }>()

const rStore = useRecipeStore()
const app = useAppStore()

watch(() => app.recipeView, () => {
  void ionContentRef.value?.$el.scrollToTop()
}, { flush: 'post' })
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title class="home-title">
          <button
            class="home-reset flex cursor-pointer items-center gap-2 transition active:text-green-800 hover:(text-green-600)"
            title="重置"
            @click="rStore.reset"
          >
            <div v-if="rStore.selectedStuff.length" i-mdi-pot-steam-outline />
            <div v-else i-mdi-pot-mix-outline />

            <span>
              <span class="home-greeting">好的，</span>今天我们来做菜！
            </span>
          </button>
        </ion-title>
        <ion-buttons slot="end">
          <RecipeViewMenu v-model="app.recipeView" />
        </ion-buttons>
      </ion-toolbar>
    </ion-header>
    <ion-content ref="ionContentRef" class="text-center" :class="{ 'cook-match-scope': app.recipeView === 'recommendation' }">
      <ChooseFood />
    </ion-content>
  </ion-page>
</template>

<style scoped>
.home-title {
  position: static;
  width: auto;
  padding-inline: 16px 0;
  font-size: 17px;
  text-align: start;
}
.home-reset {
  min-height: 44px;
}
@media (max-width: 380px) {
  .home-greeting {
    display: none;
  }
}
</style>
