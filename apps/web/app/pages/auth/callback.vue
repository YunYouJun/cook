<script lang="ts" setup>
import NativeReturnCopy from '~/components/auth/NativeReturnCopy.vue'
import { isNativeAuthCallbackUrl } from '~/utils/authInitialization'

const {
  clearNativeReturnLink,
  configurationMessage,
  errorMessage,
  initialize,
  nativeReturnLink,
  rememberNativeReturnLink,
  status,
} = useYunlefunAuth()

const router = useRouter()
const isNativeCallback = computed(() => isNativeAuthCallbackUrl(router.currentRoute.value.fullPath))
const callbackReady = computed(() => isNativeCallback.value ? Boolean(nativeReturnLink.value) : status.value === 'signed-in')

// Ionic restores its captured initial URL on Nuxt ready, after this page mounts.
watch(router.currentRoute, async (route, previous) => {
  if (!import.meta.client)
    return
  if (!isNativeAuthCallbackUrl(route.fullPath)) {
    if (previous && isNativeAuthCallbackUrl(previous.fullPath))
      clearNativeReturnLink()
    return
  }
  if (!route.hash)
    return

  rememberNativeReturnLink(window.location.origin + route.fullPath)
  await router.replace({ path: route.path, query: route.query, hash: '' })
}, { immediate: true })

onMounted(() => {
  void initialize()
})
onBeforeUnmount(() => {
  if (!isNativeCallback.value)
    clearNativeReturnLink()
})
if (import.meta.client)
  useEventListener(window, 'pagehide', clearNativeReturnLink)

const message = computed(() => {
  if (isNativeCallback.value) {
    return nativeReturnLink.value
      ? '请返回云食册继续。如未自动跳转，可复制回调链接并粘贴到应用。'
      : '未能返回云食册，请在应用中重新授权，或返回网页版登录'
  }
  return configurationMessage.value
    || errorMessage.value
    || (status.value === 'signed-in'
      ? '登录成功，正在返回'
      : status.value === 'error'
        ? '登录未完成，请返回后重试'
        : '正在验证云乐坊授权')
})
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>登录授权</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list :inset="true">
        <ion-item>
          <ion-spinner
            v-if="!isNativeCallback && (status === 'idle' || status === 'checking' || status === 'signing-in')"
            slot="start"
            name="crescent"
          />
          <ion-icon
            v-else
            slot="start"
            :color="callbackReady ? 'success' : 'danger'"
            :icon="callbackReady ? ioniconsCheckmarkCircleOutline : ioniconsAlertCircleOutline"
          />
          <ion-label class="ion-text-wrap">
            {{ message }}
          </ion-label>
        </ion-item>
        <NativeReturnCopy v-if="isNativeCallback && nativeReturnLink" :link="nativeReturnLink" />
        <ion-item v-if="isNativeCallback || configurationMessage || errorMessage" href="/my" detail>
          <ion-label>返回我的</ion-label>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
