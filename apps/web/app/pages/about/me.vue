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
        <ion-title>关于作者</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list :inset="true">
        <ion-item :href="links.yyj.bilibili" target="_blank">
          <div slot="start" class="inline-flex" i-ri-bilibili-line />
          <ion-label>哔哩哔哩</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <span m="l-1" class="inline-flex">云游君</span>
          </ion-text>
        </ion-item>

        <ion-item :href="links.yyj.github" target="_blank">
          <div slot="start" class="inline-flex" i-ri-github-line />
          <ion-label>GitHub</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            <span m="l-1" class="inline-flex">YunYouJun</span>
          </ion-text>
        </ion-item>

        <ion-item :href="links.yyj.blog" target="_blank">
          <div slot="start" class="inline-flex" i-ri-article-line />
          <ion-label>博客</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            云游君的小站
          </ion-text>
        </ion-item>

        <ion-item :href="links.yyj.mp" target="_blank">
          <div slot="start" class="inline-flex" i-ri-wechat-2-line />
          <ion-label>公众号</ion-label>
          <ion-text class="inline-flex items-center justify-center">
            云游君
          </ion-text>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
