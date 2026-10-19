<script lang="ts" setup>
import { actionSheetController, alertController, toastController } from '@ionic/vue'
import dayjs from 'dayjs'
import { customRecipes } from '~/composables/store/customRecipe'
import { links } from '~/constants'
import { appFeedbackCategories } from '~/utils/feedback'

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

const feedback = useRecipeFeedback()

/** 选类型、写几句，直接提交到 cook-feedback；提交不了时引导去 GitHub */
async function handleAppFeedback(): Promise<void> {
  const sheet = await actionSheetController.create({
    header: '想反馈什么？',
    buttons: [
      ...appFeedbackCategories.map(item => ({ text: item.label, data: item.category })),
      { text: '取消', role: 'cancel' },
    ],
  })
  await sheet.present()
  const { data: category, role } = await sheet.onDidDismiss()
  if (role === 'cancel' || !category)
    return

  const alert = await alertController.create({
    header: appFeedbackCategories.find(item => item.category === category)?.label,
    message: '不用登录。留下联系方式的话，我们处理后可以告诉你。',
    inputs: [
      { name: 'note', type: 'textarea', placeholder: '说说具体情况', attributes: { maxlength: 500 } },
      { name: 'contact', type: 'text', placeholder: '邮箱或微信（可选）', attributes: { maxlength: 100 } },
    ],
    buttons: [
      { text: '取消', role: 'cancel' },
      { text: '提交', role: 'confirm' },
    ],
  })
  await alert.present()
  const result = await alert.onDidDismiss<{ values: { note?: string, contact?: string } }>()
  const note = result.data?.values.note?.trim() ?? ''
  if (result.role !== 'confirm' || !note)
    return

  try {
    await feedback.sendFeedback({ category, note, contact: result.data?.values.contact?.trim() || undefined })
    const toast = await toastController.create({ message: '收到了，谢谢反馈', duration: 1800, position: 'top' })
    await toast.present()
  }
  catch {
    const fallback = await alertController.create({
      header: '暂时没能提交',
      message: '可以到 GitHub 提一个 issue。',
      buttons: [
        { text: '取消', role: 'cancel' },
        { text: '去 GitHub', handler: () => { window.open(links.githubIssue, '_blank') } },
      ],
    })
    await fallback.present()
  }
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
        <ion-item router-link="/recipes/mine">
          <CookListIcon slot="start" :icon="ioniconsBookOutline" color="#a2845e" />
          <ion-label>我的菜谱</ion-label>
          <ion-note v-if="customRecipes.length" slot="end">
            {{ customRecipes.length }}
          </ion-note>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item button :detail="true" @click="handleAppFeedback">
          <CookListIcon slot="start" :icon="ioniconsChatbubbleEllipsesOutline" color="#34c759" />
          <ion-label>问题反馈</ion-label>
        </ion-item>
        <ion-item :href="links.githubDiscussions" target="_blank" :detail-icon="ioniconsOpenOutline">
          <CookListIcon slot="start" :icon="ioniconsChatbubblesOutline" color="#30b0c7" />
          <ion-label>参与讨论</ion-label>
        </ion-item>
        <ion-item :href="links.contribute" target="_blank" :detail-icon="ioniconsOpenOutline">
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
