<script lang="ts" setup>
defineProps<{
  icon?: string
}>()
</script>

<template>
  <button
    class="ylf-icon-button hover:(bg-blue-300 bg-opacity-20)"
    h-10 w-10 inline-flex items-center justify-center rounded-full
  >
    <slot>
      <div v-if="icon" text-xl :class="icon" />
    </slot>
  </button>
</template>
