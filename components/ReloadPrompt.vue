<script setup lang="ts">
import { useRegisterSW } from 'virtual:pwa-register/vue'

const {
  offlineReady,
  needRefresh,
  updateServiceWorker,
} = useRegisterSW()

async function close() {
  offlineReady.value = false
  needRefresh.value = false
}
</script>

<template>
  <div
    v-if="offlineReady || needRefresh"
    class="pwa-toast rounded shadow-lg transition hover:shadow-md"
    border="~ stone-200 dark:stone-600"
    text="center"
    p="4"
    m="4"
    bg="white dark:dark-800"
    role="alert"
  >
    <div class="message" m="b-4">
      <span v-if="offlineReady">
        可以离线使用啦！
      </span>
      <span v-else>
        更新了新的内容！
      </span>
    </div>
    <button
      v-if="needRefresh"
      m="x-2" p="x-4 y-1" text="sm white"
      class="rounded shadow transition active:shadow-md"
      bg="green-500 active:green-600"
      @click="updateServiceWorker()"
    >
      更新
    </button>
    <button
      m="x-2" p="x-4 y-1" text="sm"
      class="rounded shadow transition active:shadow-md"
      border="~ stone-200 dark:stone-600"
      bg="active:(white opacity-20)"
      @click="close"
    >
      关闭
    </button>
  </div>
</template>

<style>
.pwa-toast {
  position: fixed;
  right: 0;
  bottom: 0;
  z-index: 1;
}
</style>
