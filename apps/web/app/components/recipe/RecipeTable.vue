<script lang="ts" setup>
import type { Recipes } from '~/types'

defineProps<{
  recipes: Recipes
}>()
</script>

<template>
  <table
    class="recipe-table bg-$c-bg"
    overflow="auto" h="full"
  >
    <thead>
      <tr>
        <th />
        <th>
          名称
        </th>
        <th>
          工具
        </th>
        <th>
          材料
        </th>
      </tr>
    </thead>
    <tbody>
      <RecipeTableItem
        v-for="(recipe, i) in recipes"
        :key="recipe.name"
        :index="i" :recipe="recipe"
      />
    </tbody>
  </table>
</template>

<style lang="scss">
.recipe-table {
  font-size: 0.8rem;
}

tr,
th,
td {
  border: 1px solid black;
}
</style>
