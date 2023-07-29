<script lang="ts" setup>
defineProps<{
  active: boolean
}>()
</script>

<template>
  <span
    class="rounded tag" p="x-2" border="~ yellow-200 dark:yellow-800"
    :bg="active ? 'yellow-500 dark:yellow-600 opacity-100' : 'yellow-300 opacity-20'"
    :text="active ? 'yellow-100' : 'yellow-800 dark:yellow-200'"
  >
    <slot />
  </span>
</template>
