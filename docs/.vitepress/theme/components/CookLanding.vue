<script setup lang="ts">
import CookBetaSignup from './CookBetaSignup.vue'
import CookLandingHero from './CookLandingHero.vue'
import CookShowcase from './CookShowcase.vue'

const docs = [
  { title: '使用指南', text: '快速开始和一句话找菜', link: '/guide/getting-started' },
  { title: '开发文档', text: 'CLI、菜谱数据、移动应用', link: '/dev/cli' },
  { title: '方案记录', text: '为什么这样做', link: '/decisions/' },
  { title: 'iOS 隐私政策', text: '云食册收集什么、不收集什么', link: '/privacy' },
]
</script>

<template>
  <div class="cook-landing">
    <CookLandingHero />
    <CookShowcase />
    <CookBetaSignup />
    <nav class="docs-links" aria-label="文档">
      <a v-for="doc in docs" :key="doc.link" :href="doc.link">
        <strong>{{ doc.title }}</strong>
        <span>{{ doc.text }}</span>
      </a>
    </nav>
  </div>
</template>

<style src="../landing.css" />
