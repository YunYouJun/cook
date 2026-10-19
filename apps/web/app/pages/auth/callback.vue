<script lang="ts" setup>
const {
  configurationMessage,
  errorMessage,
  initialize,
  status,
} = useYunlefunAuth()

onMounted(() => {
  void initialize()
})

const message = computed(() => configurationMessage.value
  || errorMessage.value
  || (status.value === 'signed-in'
    ? '登录成功，正在返回'
    : status.value === 'error'
      ? '登录未完成，请返回后重试'
      : '正在验证云乐坊授权'))
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
      </ion-list>
    </ion-content>
  </ion-page>
</template>
