<script setup lang="ts">
import type { PersonalAcknowledgement } from '~/constants/acknowledgements'
import { acknowledgements, personalAcknowledgements } from '~/constants/acknowledgements'

// 图标映射表（提取为常量以提升性能）
const ICON_MAP: Record<string, string> = {
  github: 'i-ri-github-line',
  bilibili: 'i-ri-bilibili-line',
  weibo: 'i-ri-weibo-line',
  twitter: 'i-ri-twitter-x-line',
  wechat: 'i-ri-wechat-2-line',
  blog: 'i-ri-article-line',
  website: 'i-ri-global-line',
  email: 'i-ri-mail-line',
}

/**
 * 根据链接类型返回对应的图标类名
 */
function getIconClass(type?: string): string {
  return ICON_MAP[type || 'website'] || 'i-ri-links-line'
}

function getLinkKey(ackIndex: number, linkIndex: number): string {
  return `link-${ackIndex}-${linkIndex}`
}

function getPersonKey(person: PersonalAcknowledgement, index: number): string {
  return person.link ? `${person.name}-${person.link}` : `${person.name}-${index}`
}
</script>

<template>
  <ion-page>
    <ion-header>
      <ion-toolbar>
        <ion-buttons slot="start">
          <ion-back-button default-href="/about" />
        </ion-buttons>
        <ion-title>致谢</ion-title>
      </ion-toolbar>
    </ion-header>

    <ion-content>
      <!-- 个人致谢名单 -->
      <ion-list-header>
        <ion-label class="text-sm op-50">
          感谢以下朋友在项目早期的支持与帮助
        </ion-label>
      </ion-list-header>
      <ion-list v-if="personalAcknowledgements.length > 0" :inset="true">
        <!-- 使用 ion-item 展示每个个人 -->
        <ion-item
          v-for="(person, index) in personalAcknowledgements"
          :key="getPersonKey(person, index)"
          :href="person.link"
          :target="person.link ? '_blank' : undefined"
          :button="!!person.link"
          :detail="!!person.link"
          lines="full"
        >
          <div slot="start" class="h-6 w-6 inline-flex items-center justify-center text-5 color-[var(--ion-color-medium)]" i-ri-user-3-line />
          <ion-label>
            <h3 class="m-0 text-[17px] color-[var(--ion-text-color)] font-normal">
              {{ person.name }}
            </h3>
          </ion-label>
        </ion-item>
      </ion-list>

      <!-- 团队/组织致谢 -->
      <template v-for="(ack, index) in acknowledgements" :key="index">
        <ion-list-header>
          <ion-label class="tracking-wide uppercase opacity-50 text-[13px]! font-medium!">
            {{ ack.description }}
          </ion-label>
        </ion-list-header>
        <ion-list :inset="true">
          <!-- 链接列表 -->
          <ion-item
            v-for="(link, linkIndex) in ack.links"
            :key="getLinkKey(index, linkIndex)"
            :href="link.href"
            :target="link.target || '_blank'"
            :detail="true"
            button
          >
            <div v-if="link.type" slot="start" class="inline-flex" :class="getIconClass(link.type)" />
            <ion-label>{{ link.label }}</ion-label>
          </ion-item>
        </ion-list>
      </template>

      <!-- 底部说明 -->
      <div class="px-4 pb-8 pt-4">
        <p class="m-0 text-center text-[13px] leading-[1.4] opacity-50">
          感谢所有支持和帮助过本项目的人们 ❤️
        </p>
      </div>
    </ion-content>
  </ion-page>
</template>

<style lang="scss" scoped>
ion-list-header {
  ion-label {
    margin-top: 0px;
    margin-bottom: 0px;
  }
}

ion-list {
  margin-top: 8px !important;
  margin-bottom: 8px !important;
}
</style>
