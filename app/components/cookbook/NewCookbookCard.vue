<script lang="ts" setup>
definePageMeta({
  layout: 'child',
  title: '新建食谱书',
})
</script>

<template>
  <NuxtLink
    class="bg-$c-bg-alt"
    inline-flex h-36 w-full cursor-pointer shadow items-center justify-center
    to="/cookbooks/new"
  >
    <slot>
      <div i-ri-add-line />
    </slot>
  </NuxtLink>
</template>
