<script lang="ts" setup>
defineProps<{
  icon: string
  label: string
  to: string
}>()
</script>

<template>
  <NuxtLink
    :to="to"
    flex="~ col"
    border="~ solid dark:$ylf-c-border"
    bg="$ylf-c-bg-alt"
    class="inline-flex items-center justify-center rounded-md px-4 py-2 text-sm font-medium decoration-none hover-bg-gray-100 focus:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 focus-visible:ring-blue-500 dark:hover:bg-dark-400"
  >
    <div :class="icon" inline-flex text-lg />
    <div mt-2 inline-flex text-xs>
      {{ label }}
    </div>
  </NuxtLink>
</template>
