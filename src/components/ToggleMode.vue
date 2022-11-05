<script lang="ts" setup>
import type { SearchMode } from '~/store/recipe'
import { useRecipeStore } from '~/store/recipe'

const rStore = useRecipeStore()

const searchModes: {
  id: SearchMode
  name: string
}[] = [{
  id: 'loose',
  name: '模糊匹配',
}, {
  id: 'strict',
  name: '严格匹配',
}, {
  id: 'survival',
  name: '生存模式',
}]
</script>

<template>
  <div>
    <button
      v-for="mode in searchModes" :key="mode.id" class="tag rounded px-2"
      :bg="mode.id === rStore.curMode ? 'orange-500 dark:orange-600 opacity-100' : 'orange-300 opacity-20'"
      :text="mode.id === rStore.curMode ? 'orange-100' : 'orange-800 dark:orange-200'"
      @click="rStore.setMode(mode.id)"
    >
      {{ mode.name }}
    </button>
  </div>
</template>
