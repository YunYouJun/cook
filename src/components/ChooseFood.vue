<script lang="ts" setup>
import recipeData from '~/data/recipe.json'
import type { Recipe } from '~/types'
const recipe = ref(recipeData as Recipe)
</script>

<template>
  <div>
    <span v-for="item, i in recipe" :key="i" class="tag">{{ item.name }}</span>
  </div>
</template>
