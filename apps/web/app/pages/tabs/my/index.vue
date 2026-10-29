<script lang="ts" setup>
import dayjs from 'dayjs'
import { customRecipes } from '~/composables/store/customRecipe'
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
    <ion-header :translucent="true">
      <ion-toolbar>
        <ion-title>我的</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content :fullscreen="true">
      <ion-header collapse="condense">
        <ion-toolbar>
          <ion-title size="large">
            我的
          </ion-title>
        </ion-toolbar>
      </ion-header>

      <ion-list :inset="true">
        <ion-item v-if="isAuthenticated && account" class="my-account" lines="full">
          <ion-avatar v-if="account.avatarUrl" slot="start">
            <img :alt="account.displayName" :src="account.avatarUrl">
          </ion-avatar>
          <ion-icon v-else slot="start" class="my-account-placeholder" :icon="ioniconsPersonCircle" />
          <ion-label>
            <h2>{{ account.displayName }}</h2>
            <p>云乐坊账号</p>
          </ion-label>
        </ion-item>
        <ion-item
          v-if="isAuthenticated"
          :button="syncStatus === 'error'"
          :detail="false"
          @click="handleSyncClick"
        >
          <span v-if="syncStatus === 'syncing'" slot="start" class="my-sync-spinner">
            <ion-spinner name="crescent" />
          </span>
          <CookListIcon
            v-else
            slot="start"
            :icon="syncStatus === 'error' ? ioniconsCloudOfflineOutline : ioniconsCloudDoneOutline"
            :color="syncStatus === 'error' ? '#ff3b30' : '#34c759'"
          />
          <ion-label>
            <h2>收藏和历史云同步</h2>
            <p>{{ syncText }}</p>
          </ion-label>
        </ion-item>
        <ion-item
          v-if="!isAuthenticated"
          :button="true"
          :disabled="authBusy || Boolean(configurationMessage)"
          @click="handleSignIn"
        >
          <span v-if="authBusy" slot="start" class="my-sync-spinner">
            <ion-spinner name="crescent" />
          </span>
          <CookListIcon v-else slot="start" :icon="ioniconsLogInOutline" color="#007aff" />
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
          <CookListIcon slot="start" :icon="ioniconsTimeOutline" color="#5856d6" />
          <ion-label>历史记录</ion-label>
        </ion-item>
        <ion-item router-link="/recipes/favorites">
          <CookListIcon slot="start" :icon="ioniconsStarOutline" color="#ff9500" />
          <ion-label>我的收藏</ion-label>
        </ion-item>
        <ion-item :button="true" router-link="/catalog">
          <CookListIcon slot="start" color="#34c759">
            <span class="i-mdi-carrot h-4.5 w-4.5" />
          </CookListIcon>
          <ion-label>食材和厨具</ion-label>
        </ion-item>
        <ion-item router-link="/recipes/mine">
          <CookListIcon slot="start" :icon="ioniconsBookOutline" color="#a2845e" />
          <ion-label>我的菜谱</ion-label>
          <ion-note v-if="customRecipes.length" slot="end">
            {{ customRecipes.length }}
          </ion-note>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item router-link="/feedback" :detail="true">
          <CookListIcon slot="start" :icon="ioniconsChatbubbleEllipsesOutline" color="#34c759" />
          <ion-label><h2>问题反馈</h2><p>私密发送给云乐坊团队</p></ion-label>
        </ion-item>
        <ion-item :href="links.support" target="_blank" rel="noopener noreferrer" :detail-icon="ioniconsOpenOutline">
          <CookListIcon slot="start" :icon="ioniconsHelpCircleOutline" color="#007aff" />
          <ion-label>支持中心与需求墙</ion-label>
        </ion-item>
        <ion-item router-link="/feedback?category=recipe" :detail="true">
          <CookListIcon slot="start" :icon="ioniconsMailOutline" color="#ff2d55" />
          <ion-label>菜谱投稿</ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item router-link="/settings">
          <CookListIcon slot="start" :icon="ioniconsSettingsOutline" color="#8e8e93" />
          <ion-label>设置</ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item router-link="/changelog">
          <CookListIcon slot="start" :icon="ioniconsDocumentTextOutline" color="#af52de" />
          <ion-label>更新日志</ion-label>
        </ion-item>
        <ion-item router-link="/help">
          <CookListIcon slot="start" :icon="ioniconsHelpCircleOutline" color="#007aff" />
          <ion-label>帮助</ion-label>
        </ion-item>
        <ion-item router-link="/about">
          <CookListIcon slot="start" :icon="ioniconsInformationCircleOutline" color="#5ac8fa" />
          <ion-label>关于</ion-label>
        </ion-item>
        <ion-item :href="links.legacyCook" :detail="true" :detail-icon="ioniconsOpenOutline">
          <CookListIcon slot="start" :icon="ioniconsArrowUndoOutline" color="#8e8e93" />
          <ion-label>切换到旧版</ion-label>
          <ion-note slot="end">
            v1
          </ion-note>
        </ion-item>
      </ion-list>

      <ion-list v-if="isAuthenticated" :inset="true">
        <ion-item :button="true" :detail="false" :disabled="authBusy" @click="handleSignOut">
          <ion-label class="ion-text-center" color="danger">
            退出登录
          </ion-label>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>

<style scoped>
.my-account {
  --padding-top: 6px;
  --padding-bottom: 6px;
}
.my-account ion-avatar {
  width: 56px;
  height: 56px;
  margin-inline-end: 14px;
}
.my-account-placeholder {
  font-size: 56px;
  margin-inline-end: 14px;
  color: var(--ion-color-medium);
}
.my-account h2 {
  font-size: 20px;
  font-weight: 600;
}
.my-sync-spinner {
  display: inline-flex;
  justify-content: center;
  width: 29px;
  margin-inline-end: 14px;
}
</style>
