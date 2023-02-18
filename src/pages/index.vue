<script lang="ts" setup>
import { useRecipeStore } from '~/store/recipe'
const rStore = useRecipeStore()
</script>

<template>
  <div>
    <div text-4xl m="t-4">
      <button
        class="cursor-pointer transition hover:(text-green-600) active:text-green-800"
        title="重置"
        @click="rStore.reset"
      >
        <div v-if="rStore.selectedStuff.length" i-mdi-pot-steam-outline />
        <div v-else i-mdi-pot-mix-outline />
      </button>
    </div>
    <p text="sm" m="b-4">
      好的，今天我们来做菜！
    </p>
    <p>
      <ChooseFood />
    </p>
  </div>
</template>
