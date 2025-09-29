<script lang="ts" setup>
defineProps<{
  icon?: string
}>()
</script>

<template>
  <button
    class="ylf-icon-button hover:(bg-blue-300 bg-opacity-20)"
    rounded-full inline-flex h-10 w-10 items-center justify-center
  >
    <slot>
      <div v-if="icon" text-xl :class="icon" />
    </slot>
  </button>
</template>
