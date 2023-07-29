<script lang="ts" setup>
defineProps<{
  active: boolean
}>()
</script>

<template>
  <span
    class="meat-tag rounded tag" p="x-2"
    border="~ red-200 dark:red-800"
    :bg="active ? 'red-500 opacity-90' : 'red-300 opacity-20'"
    :text="active ? 'red-100' : 'red-800 dark:red-200'"
  >
    <slot />
  </span>
</template>
