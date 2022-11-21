<script lang="ts" setup>
import { links } from '~/constants'
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
      <a
        m="t-2"
        border="b-1 dashed"
        class="inline-flex text-sm text-blue-600 dark:text-blue-400"
        :href="links.contribute" target="_blank"
        title="居家菜谱投稿"
      >
        立即投稿
      </a>
    </p>
  </div>
</template>
