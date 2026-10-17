<script lang="ts" setup>
/**
 * iOS 设置风格的列表图标：彩色圆角方块 + 白色线框图标
 */
defineProps<{
  icon: string
  color: string
}>()
</script>

<template>
  <span class="cook-list-icon" :style="{ backgroundColor: color }" aria-hidden="true">
    <ion-icon :icon="icon" />
  </span>
</template>

<style scoped>
.cook-list-icon {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 29px;
  height: 29px;
  margin-inline-end: 14px;
  border-radius: 7px;
  color: #fff;
  font-size: 18px;
}
</style>
