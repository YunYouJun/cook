<script setup lang="ts">
import { links } from '~/constants'

definePageMeta({
  alias: ['/apps'],
})

useHead({
  title: '发现',
})
</script>

<template>
  <ion-page flex flex-col>
    <ion-header>
      <ion-toolbar>
        <ion-title>
          发现
        </ion-title>
      </ion-toolbar>
    </ion-header>
    <ion-content>
      <ion-list :inset="true">
        <ion-item router-link="/apps/random">
          <ion-icon slot="start" :icon="ioniconsShuffleOutline" />
          <ion-label>
            今天吃什么
          </ion-label>
        </ion-item>

        <!-- <ion-item router-link="/apps/random">
          <ion-icon slot="start" :icon="ioniconsChatboxEllipsesOutline" />
          <ion-label>
            AI 菜谱
          </ion-label>
        </ion-item> -->
      </ion-list>

      <ion-list :inset="true">
        <ion-item :href="links.githubIssue" target="_blank">
          <ion-label>
            新功能建议
          </ion-label>
        </ion-item>
      </ion-list>
    </ion-content>
  </ion-page>
</template>
