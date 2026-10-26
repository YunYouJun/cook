<script setup lang="ts">
import { appStoreId, iosAppName, links } from '~/constants'

const appStoreUrl = appStoreId ? `https://apps.apple.com/cn/app/id${appStoreId}` : ''
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/my" />
        </ion-buttons>
        <ion-title>关于</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <TheAboutList />

      <ion-list :inset="true">
        <ion-item v-if="appStoreUrl" :href="appStoreUrl" target="_blank">
          <ion-label>iOS 版</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <div class="inline-flex" i-ri-apple-line />
            <span m="l-1" class="inline-flex">{{ iosAppName }}</span>
          </ion-text>
        </ion-item>

        <ion-item :href="links.cook" target="_blank">
          <ion-label>网页版本</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <div class="inline-flex" i-ri-global-line />
            <span m="l-1" class="inline-flex">cook.yunyoujun.cn</span>
          </ion-text>
        </ion-item>

        <ion-item :href="links.yyj.bilibiliOpus" target="_blank">
          <ion-label>旅程的起点</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <div class="inline-flex" i-ri-bilibili-line />
            <span m="l-1" class="inline-flex">动态</span>
          </ion-text>
        </ion-item>

        <ion-item :href="links.yyj.mpArticle" target="_blank">
          <ion-label>关于食用手册的前世今生</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <div class="inline-flex" i-ri-wechat-2-line />
            <span m="l-1" class="inline-flex">文章</span>
          </ion-text>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-list-header>
          <ion-label>开发者</ion-label>
        </ion-list-header>
        <ion-item :href="links.githubIssue" target="_blank">
          <ion-label>提 Issue</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <div class="inline-flex" i-ri-github-line />
            <span m="l-1" class="inline-flex">GitHub</span>
          </ion-text>
        </ion-item>
        <ion-item :href="links.githubDiscussions" target="_blank">
          <ion-label>参与讨论</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <div class="inline-flex" i-ri-discuss-line />
            <span m="l-1" class="inline-flex">Discussions</span>
          </ion-text>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
