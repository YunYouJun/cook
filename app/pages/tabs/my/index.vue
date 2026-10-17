<script lang="ts" setup>
import dayjs from 'dayjs'
import { links } from '~/constants'

definePageMeta({
  alias: ['/my'],
})

const {
  account,
  configurationMessage,
  errorMessage,
  isAuthenticated,
  signIn,
  signOut,
  status,
} = useYunlefunAuth()

const authBusy = computed(() => status.value === 'checking' || status.value === 'signing-in')

const {
  errorMessage: syncErrorMessage,
  lastSyncedAt,
  status: syncStatus,
  syncNow,
} = useUserDataSync()

const syncText = computed(() => {
  switch (syncStatus.value) {
    case 'syncing':
      return '正在同步…'
    case 'synced':
      return `已同步 ${dayjs(lastSyncedAt.value).format('HH:mm')}`
    case 'error':
      return `${syncErrorMessage.value}，点击重试`
    default:
      return '等待同步'
  }
})

function handleSyncClick(): void {
  if (syncStatus.value === 'error')
    syncNow()
}

async function handleSignIn(): Promise<void> {
  await signIn()
}

async function handleSignOut(): Promise<void> {
  await signOut()
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>我的</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list :inset="true">
        <ion-item v-if="isAuthenticated && account">
          <ion-avatar v-if="account.avatarUrl" slot="start">
            <img :alt="account.displayName" :src="account.avatarUrl">
          </ion-avatar>
          <ion-icon v-else slot="start" :icon="ioniconsPersonCircleOutline" />
          <ion-label>
            <h2>{{ account.displayName }}</h2>
            <p>云乐坊账号</p>
          </ion-label>
        </ion-item>
        <ion-item
          v-if="isAuthenticated"
          :button="syncStatus === 'error'"
          @click="handleSyncClick"
        >
          <ion-spinner v-if="syncStatus === 'syncing'" slot="start" name="crescent" />
          <ion-icon
            v-else
            slot="start"
            :color="syncStatus === 'error' ? 'danger' : undefined"
            :icon="syncStatus === 'error' ? ioniconsCloudOfflineOutline : ioniconsCloudDoneOutline"
          />
          <ion-label>
            <h2>收藏和历史云同步</h2>
            <p>{{ syncText }}</p>
          </ion-label>
        </ion-item>
        <ion-item
          v-if="isAuthenticated"
          :button="true"
          :disabled="authBusy"
          @click="handleSignOut"
        >
          <ion-icon slot="start" :icon="ioniconsLogOutOutline" />
          <ion-label>退出登录</ion-label>
        </ion-item>
        <ion-item
          v-else
          :button="true"
          :disabled="authBusy || Boolean(configurationMessage)"
          @click="handleSignIn"
        >
          <ion-spinner v-if="authBusy" slot="start" name="crescent" />
          <ion-icon v-else slot="start" :icon="ioniconsLogInOutline" />
          <ion-label>
            <h2>{{ authBusy ? '正在检查登录状态' : '使用云乐坊账号登录' }}</h2>
            <p>安全授权，无需在 Cook 输入密码</p>
          </ion-label>
        </ion-item>
        <ion-item v-if="configurationMessage || errorMessage" lines="none">
          <ion-icon slot="start" color="danger" :icon="ioniconsAlertCircleOutline" />
          <ion-label class="ion-text-wrap" color="danger">
            {{ configurationMessage || errorMessage }}
          </ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item router-link="/recipes/history">
          <ion-icon slot="start" :icon="ioniconsTimeOutline" />
          <ion-label>历史记录</ion-label>
        </ion-item>
        <ion-item router-link="/recipes/favorites">
          <ion-icon slot="start" :icon="ioniconsStarOutline" />
          <ion-label>我的收藏</ion-label>
        </ion-item>
        <!-- <ion-item router-link="/cookbooks">
          <ion-icon slot="start" :icon="ioniconsBookOutline" />
          <ion-label>自定义菜谱</ion-label>
        </ion-item> -->
      </ion-list>

      <ion-list :inset="true">
        <ion-item :href="links.githubIssue" target="_blank">
          <ion-icon slot="start" :icon="ioniconsChatbubbleEllipsesOutline" />
          <ion-label>问题反馈</ion-label>
        </ion-item>
        <ion-item :href="links.githubDiscussions" target="_blank">
          <ion-icon slot="start" :icon="ioniconsChatbubblesOutline" />
          <ion-label>参与讨论</ion-label>
        </ion-item>
        <ion-item :href="links.contribute" target="_blank">
          <ion-icon slot="start" :icon="ioniconsMailOutline" />
          <ion-label>菜谱投稿</ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item router-link="/changelog">
          <ion-icon slot="start" :icon="ioniconsDocumentTextOutline" />
          <ion-label>更新日志</ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item router-link="/settings">
          <ion-icon slot="start" :icon="ioniconsSettingsOutline" />
          <ion-label>设置</ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item router-link="/help">
          <ion-icon slot="start" :icon="ioniconsHelpCircleOutline" />
          <ion-label>帮助</ion-label>
        </ion-item>
        <ion-item router-link="/about">
          <ion-icon slot="start" :icon="ioniconsInformationCircleOutline" />
          <ion-label>关于</ion-label>
        </ion-item>
      </ion-list>

      <!-- <YlfForm>
        <YlfFormItem icon="i-ri-article-line" label="自定义菜谱 TODO" to="/cookbooks/" />
      </YlfForm> -->
    </ion-content>
  </ion-page>
</template>
