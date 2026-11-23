<script lang="ts" setup>
import { computed, onMounted, shallowRef } from 'vue'
import { cookAndroidIntentUrl, supportsAndroidBrowserIntent } from '~/utils/androidBrowserIntent'

const props = defineProps<{ recipeId?: number }>()
const supportsIntent = shallowRef(false)

onMounted(() => {
  supportsIntent.value = supportsAndroidBrowserIntent(navigator.userAgent)
})

const href = computed(() => {
  const id = props.recipeId
  if (!supportsIntent.value || id === undefined || !Number.isInteger(id) || id < 0 || id > 2_147_483_647)
    return undefined

  return cookAndroidIntentUrl(`https://cook.yunle.fun/recipes/share/?id=${id}`)
})
</script>

<template>
  <ion-item v-if="href" :href="href" :detail="true">
    <ion-label>在云食册中打开</ion-label>
  </ion-item>
</template>
