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
        <ion-title>更新日志</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <ion-list-header>功能日志</ion-list-header>
      <ion-list :inset="true">
        <ion-item>
          <ion-label>
            <h2>Web v2.0.0</h2>
            <p class="ion-text-wrap">
              按食材、厨具和忌口找菜，支持经典选菜与工作台两种布局。
            </p>
            <p class="ion-text-wrap">
              收藏、历史和私人菜谱；登录云乐坊账号后可同步会员收藏与历史。
            </p>
            <p class="ion-text-wrap">
              新增经审核的饮食目标、隔夜带饭和机构低 GI 菜谱筛选，具体依据见菜谱说明。
            </p>
            <p class="ion-text-wrap">
              首页与关于页可参与云食册 iOS TestFlight 测试。
            </p>
          </ion-label>
        </ion-item>
      </ion-list>

      <ion-list :inset="true">
        <ion-item :href="links.releaseNotes" target="_blank" rel="noopener noreferrer">
          <ion-label>完整版本记录</ion-label>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
