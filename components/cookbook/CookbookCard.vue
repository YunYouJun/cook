<script lang="ts" setup>
import type { Cookbook } from '~/types'

defineProps<{
  cookbook: Cookbook
}>()

const showDetail = ref(false)
</script>

<template>
  <button
    class="bg-$c-bg-alt"
    h-36 w-full inline-flex cursor-pointer items-center justify-center shadow
    @click="showDetail = true"
  >
    <slot />
  </button>

  <CookbookDetail
    v-if="showDetail"
    absolute bottom-17 left-2 right-2 top-2 z-1 overflow-hidden shadow
    :cookbook="cookbook"
  >
    <YlfIconButton
      icon="i-ri-close-line"
      class="absolute right-2 top-2"
      @click="showDetail = false"
    />
  </CookbookDetail>
</template>
