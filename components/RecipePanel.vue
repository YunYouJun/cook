<script lang="ts" setup>
const recipePanel = ref()
const rStore = useRecipeStore()

const { isVisible, show } = useInvisibleElement(recipePanel)
</script>

<template>
  <Transition>
    <BasketButton :is-visible="isVisible" @click="show" />
  </Transition>

  <div ref="recipePanel" m="2 t-4" p="2" class="recipe-panel relative shadow transition hover:shadow-md" bg="gray-400/8">
    <h2 text="xl" font="bold" p="1">
      🍲 来看看组合出的菜谱吧！
    </h2>

    <ToggleMode />

    <!-- <Switch /> -->
    <div class="cook-recipes" p="2">
      <SearchFoodInput />

      <Transition mode="out-in">
        <div class="cook-filter-recipes">
          <span v-if="!rStore.selectedStuff.length && !rStore.curTool" text="sm" p="2">
            你要先选食材或工具哦～
          </span>

          <span v-else-if="rStore.displayedRecipe.length">
            <DishTag v-for="item, i in rStore.displayedRecipe" :key="i" :dish="item" />
          </span>

          <span v-else text="sm">
            还没有完美匹配的菜谱呢……
            <br>
            大胆尝试一下，或者<a href="#" @click="rStore.reset()">
              <strong>换个组合</strong></a>？
            <br>
            <span m="t-1">欢迎来
              <a class="font-bold text-blue-600 dark:text-blue-400" href="https://docs.qq.com/sheet/DQk1vdkhFV0twQVNS?tab=uykkic" target="_blank">这里</a>
              反馈新的菜谱！
            </span>
          </span>
        </div>
      </Transition>

      <hr m="y-2">

      <RandomRecipe />
    </div>
  </div>
</template>
