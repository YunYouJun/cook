<script lang="ts" setup>
definePageMeta({
  layout: 'child',
  title: '新建食谱书',
})
</script>

<template>
  <NuxtLink
    class="bg-$c-bg-alt"
    h-36 w-full inline-flex cursor-pointer items-center justify-center shadow
    to="/cookbooks/new"
  >
    <slot>
      <div i-ri-add-line />
    </slot>
  </NuxtLink>
</template>
