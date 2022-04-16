<script lang="ts" setup>
import { useRecipeStore } from '~/stores/recipe'
const rStore = useRecipeStore()
</script>

<template>
  <div>
    <div text-4xl m="t-4">
      <button
        class="cursor-pointer transition hover:(text-green-600) active:text-green-800"
        @click="rStore.reset"
      >
        <div v-if="rStore.selectedStuff.length" i-mdi-pot-steam-outline />
        <div v-else i-mdi-pot-mix-outline />
      </button>
    </div>
    <p text="sm" m="b-4">
      好的，今天我们来做菜！
    </p>
    <p text="sm" opacity="80">
      选中你囤的食材和有的厨具，看看今天能做什么菜吧！
    </p>
    <p>
      <ChooseFood />
    </p>
  </div>
</template>
