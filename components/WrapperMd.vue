<script lang="ts" setup>
defineProps<{
  frontmatter: {
    title: string
  }
}>()
</script>

<template>
  <div m="t-4" class="m-auto max-w-900px text-left">
    <h3 text="center 3xl" font="serif !black">
      {{ frontmatter?.title }}
    </h3>
    <slot class="markdown-body" />
  </div>
</template>
