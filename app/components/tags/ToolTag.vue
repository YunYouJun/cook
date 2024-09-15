<script lang="ts" setup>
defineProps<{
  active: boolean
}>()
</script>

<template>
  <span
    class="rounded tag" p="x-2"
    border="~ stone-200 dark:stone-600"
    :bg="active ? 'stone-600 opacity-100' : 'stone-300 opacity-5'"
    :text="active ? 'stone-100' : 'stone-800 dark:stone-200'"
  >
    <slot />
  </span>
</template>
