<script lang="ts" setup>
const rStore = useRecipeStore()

const searchInput = ref<HTMLInputElement>()
onMounted(() => {
  searchInput.value?.focus()
})
</script>

<template>
  <div m="auto b-2" max-w="500px">
    <div relative text-xs>
      <div
        v-if="rStore.keyword" cursor="pointer"
        absolute right-2 inline-flex justify="center" items-center h="full" opacity="70"
        @click="rStore.clearKeyWord()"
      >
        <div i-ri-close-line />
      </div>
      <input
        id="input"
        ref="searchInput"
        v-model="rStore.keyword"
        placeholder="关键字过滤"
        aria-label="搜索关键字"
        type="text"
        autocomplete="false"
        p="x4 y2"
        w="full"
        text="center"
        bg="white dark:dark-800"
        border="~ rounded gray-200 dark:gray-700"
        class="focus:dark:gray-500"
      >
      <label class="hidden" for="input">快速搜索</label>
    </div>
  </div>
</template>
