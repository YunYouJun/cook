<script lang="ts" setup>
defineProps<{
  active: boolean
}>()
</script>

<template>
  <span
    class="vegetable-tag rounded tag" p="x-2"
    border="~ green-200 dark:green-800"
    :bg="active ? 'green-600 opacity-90' : 'green-300 opacity-20'"
    :text="active ? 'green-100' : 'green-800 dark:green-200'"
  >
    <slot />
  </span>
</template>
