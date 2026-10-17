<script lang="ts" setup>
import { useStaggerEnter } from '~/composables/animation'
import { recipePanelRef } from '~/composables/global'

const rStore = useRecipeStore()
const showSearchInput = shallowRef(Boolean(rStore.keyword))
const showTooltip = computed(() => !rStore.selectedStuff.length && !rStore.curTool)

const onBeforeEnter = useStaggerEnter()

// 菜名有重名，拼上视频号并为重复项编号，保证列表过渡的 key 稳定且唯一
const keyedRecipes = computed(() => {
  const seen = new Map<string, number>()
  return rStore.displayedRecipe.map((dish) => {
    const base = `${dish.bv ?? ''}:${dish.name}`
    const count = seen.get(base) ?? 0
    seen.set(base, count + 1)
    return { key: count ? `${base}:${count}` : base, dish }
  })
})
</script>

<template>
  <section
    ref="recipePanelRef" aria-label="经典菜谱结果"
    class="classic-recipe-panel relative shadow transition hover:shadow-md" m="x-2 y-4" p="2" bg="gray-400/8"
  >
    <RecipePanelTitle />
    <ToggleMode />
    <button
      class="classic-search-toggle" :aria-expanded="showSearchInput" aria-label="搜索菜名"
      @click="showSearchInput = !showSearchInput"
    >
      <div v-if="!showSearchInput" i-ri-search-line />
      <div v-else i-ri-search-fill />
    </button>
    <div class="cook-recipes" p="2">
      <SearchFoodInput v-if="showSearchInput" />
      <div v-if="rStore.loadError" role="alert">
        <p>{{ rStore.loadError }}</p>
        <button class="classic-retry" @click="rStore.loadRecipes">
          重新加载菜谱
        </button>
      </div>
      <span v-else-if="showTooltip" text="sm" p="2">你要先选食材或工具哦～</span>
      <div v-else-if="rStore.isSearching" role="status" p="6">
        正在准备本地菜谱…
      </div>
      <TransitionGroup v-else-if="keyedRecipes.length" tag="div" name="recipe-list" appear @before-enter="onBeforeEnter">
        <DishTag v-for="item in keyedRecipes" :key="item.key" :dish="item.dish" />
      </TransitionGroup>
      <div v-else text="sm" role="status">
        <p>还没有完美匹配的菜谱呢……</p>
        <p>
          大胆尝试一下，或者 <button @click="rStore.reset">
            <strong>换个组合</strong>
          </button>？
        </p>
        <p m="t-1">
          欢迎来 <a class="text-blue-600 font-bold dark:text-blue-400" href="https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS?tab=uykkic" target="_blank" rel="noopener noreferrer">这里</a> 反馈新的菜谱！
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.classic-recipe-panel {
  scroll-margin-top: 12px;
}
.classic-search-toggle {
  position: absolute;
  top: 8px;
  right: 8px;
  display: grid;
  place-items: center;
  min-width: 44px;
  min-height: 44px;
}
.classic-retry {
  min-height: 44px;
  text-decoration: underline;
}
@media (max-width: 380px) {
  .classic-search-toggle {
    position: static;
    margin-left: auto;
  }
}
</style>
