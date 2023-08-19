<script lang="ts" setup>
definePageMeta({
  layout: 'child',
  title: '自定义菜谱',
})

const route = useRoute()
const id = computed(() => route.query.id)
</script>

<template>
  <div>
    <h3>
      开发中，敬请期待
    </h3>

    <div grid="~ cols-3" gap="4" p="4">
      <CookbookCard :cookbook="defaultCookbook">
        默认菜谱
      </CookbookCard>

      <NewCookbookCard />
    </div>
  </div>
</template>
