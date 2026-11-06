<script setup lang="ts">
defineEmits<{ retry: [] }>()
</script>

<template>
  <ion-page>
    <ion-content>
      <div class="cook-empty" role="alert">
        <h2>暂时无法切换做菜记录</h2>
        <p>浏览器存储可能已满或不可用，原账号记录已保留。</p>
        <p>请恢复浏览器存储后重试，期间暂停显示和修改记录。</p>
        <ion-button @click="$emit('retry')">
          重试切换
        </ion-button>
      </div>
    </ion-content>
  </ion-page>
</template>
