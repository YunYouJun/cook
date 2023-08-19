<script lang="ts" setup>
import type { Cookbook } from '~/types'

const props = defineProps<{
  cookbook: Cookbook
}>()

const recipes = ref<Cookbook['recipes']>(props.cookbook.recipes)
onMounted(async () => {
  recipes.value = ((await import('../../data/recipe.json')).default) as unknown as Cookbook['recipes']
})
</script>

<template>
  <div class="bg-$c-bg-alt" flex="~ col">
    <h3 mt-4 font-bold>
      {{ cookbook.title }}
    </h3>
    <sub op="90" my-3>
      {{ cookbook.description }}
    </sub>
    <div mx-auto mt-2 p-0 border="1px" overflow-y="scroll">
      <RecipeTable h="full" :recipes="recipes" />
    </div>
    <slot />
  </div>
</template>
