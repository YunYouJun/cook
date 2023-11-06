<script lang="ts" setup>
import { NuxtLink } from '#components'

defineProps<{
  icon?: string
  label?: string
  /**
   * Router link
   */
  to?: string
}>()
</script>

<template>
  <component
    :is="to ? NuxtLink : 'div'"
    :to="to"
    class="ylf-form-item"
    w-full flex cursor-pointer items-center justify-between p-2
    hover:bg-gray-100
    dark:hover:bg-dark-400
  >
    <div v-if="label" class="text-sm" inline-flex items-center justify-center>
      <div v-if="icon" :class="icon" mr-2 inline-flex />
      <span>{{ label }}</span>
    </div>
    <div inline-flex>
      <slot>
        <div v-if="to" i-ri-arrow-right-s-line />
      </slot>
    </div>
  </component>
</template>
