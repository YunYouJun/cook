<script lang="ts" setup>
import { links } from '~/constants'

definePageMeta({
  alias: ['/my'],
})
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-title>我的</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <!-- TODO -->
      <ion-list :inset="true">
        <ion-item router-link="/recipes/history">
          <ion-icon slot="start" :icon="ioniconsTimeOutline" />
          <ion-label>历史记录</ion-label>
        </ion-item>
        <!-- <ion-item router-link="/recipes/collect">
          <ion-icon slot="start" :icon="ioniconsStarOutline" />
          <ion-label>我的收藏</ion-label>
        </ion-item>
        <ion-item router-link="/cookbooks">
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
        <ion-item :href="links.changelog" target="_blank">
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
