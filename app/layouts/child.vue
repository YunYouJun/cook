<script lang="ts" setup>
defineProps<{
  title?: string
}>()

const route = useRoute()
</script>

<template>
  <main class="text-gray-700 text-center dark:text-gray-200" p="t-5 b-15">
    <div flex items-center justify-between>
      <BackBtn ml-3 />
      <h2 text-lg flex items-center justify-center font="bold">
        {{ route.meta.title }}
      </h2>
      <DarkToggle mr-3 />
    </div>

    <slot />
    <TheBottomMenu bottom-0 left-0 right-0 fixed />
  </main>
</template>
