<script setup lang="ts">
// const router = useRouter()
</script>

<template>
  <main p="x4 y10" text="center green-700 dark:gray-200">
    <div text-4xl>
      <div i-ri-error-warning-line inline-block />
    </div>
    <div>菜谱消失了</div>
    <div>
      <NuxtLink text-sm btn m="3 t8" to="/">
        返回主页
      </NuxtLink>
    </div>
  </main>
</template>
