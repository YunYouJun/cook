<script setup lang="ts">
import { links } from '~/constants'
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
    </ion-content>
  </ion-page>
</template>
