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
    class="text-sm font-medium px-4 py-2 rounded-md decoration-none inline-flex items-center justify-center focus:outline-none hover-bg-gray-100 focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 dark:hover:bg-dark-400"
  >
    <div :class="icon" text-lg inline-flex />
    <div text-xs mt-2 inline-flex>
      {{ label }}
    </div>
  </NuxtLink>
</template>
