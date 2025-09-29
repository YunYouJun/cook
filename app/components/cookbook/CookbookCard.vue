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
    inline-flex h-36 w-full cursor-pointer shadow items-center justify-center
    @click="showDetail = true"
  >
    <slot />
  </button>

  <CookbookDetail
    v-if="showDetail"
    shadow bottom-17 left-2 right-2 top-2 absolute z-1 overflow-hidden
    :cookbook="cookbook"
  >
    <YlfIconButton
      icon="i-ri-close-line"
      class="right-2 top-2 absolute"
      @click="showDetail = false"
    />
  </CookbookDetail>
</template>
