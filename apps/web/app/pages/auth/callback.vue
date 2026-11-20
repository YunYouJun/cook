<script lang="ts" setup>
import { onIonViewWillLeave } from '@ionic/vue'
import { isAuthCallbackUrl } from '~/utils/authInitialization'

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
const copyMessage = shallowRef('')

// Ionic restores its captured initial URL on Nuxt ready, after this page mounts.
watch(() => router.currentRoute.value, async (route) => {
  if (import.meta.client && isAuthCallbackUrl(route.path) && route.query.platform === 'native' && route.hash) {
    rememberNativeReturnLink(window.location.origin + route.fullPath)
    await router.replace({ path: route.path, query: route.query, hash: '' })
  }
}, { immediate: true })

onMounted(() => {
  void initialize()
})

function clearNativeReturnState() {
  clearNativeReturnLink()
  copyMessage.value = ''
}

onIonViewWillLeave(clearNativeReturnState)
onBeforeUnmount(clearNativeReturnState)

async function copyNativeReturnLink() {
  try {
    await navigator.clipboard.writeText(nativeReturnLink.value)
    copyMessage.value = '已复制，请返回云食册粘贴'
  }
  catch {
    copyMessage.value = '无法复制链接，请返回云食册重试'
  }
}

const message = computed(() => {
  if (nativeReturnLink.value)
    return '请返回云食册继续。如未自动跳转，可复制回调链接并粘贴到应用。'
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
            v-if="status === 'idle' || status === 'checking' || status === 'signing-in'"
            slot="start"
            name="crescent"
          />
          <ion-icon
            v-else
            slot="start"
            :color="status === 'signed-in' ? 'success' : 'danger'"
            :icon="status === 'signed-in' ? ioniconsCheckmarkCircleOutline : ioniconsAlertCircleOutline"
          />
          <ion-label class="ion-text-wrap">
            {{ message }}
          </ion-label>
        </ion-item>
        <ion-item v-if="nativeReturnLink" button @click="copyNativeReturnLink">
          <ion-label>复制回调链接</ion-label>
        </ion-item>
        <ion-item v-if="copyMessage" role="status">
          <ion-label>{{ copyMessage }}</ion-label>
        </ion-item>
        <ion-item v-if="configurationMessage || errorMessage" href="/my" detail>
          <ion-label>返回我的</ion-label>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
