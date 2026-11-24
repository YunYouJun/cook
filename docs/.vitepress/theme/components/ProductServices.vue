<script setup lang="ts">
import { iosVersion, links } from '../constants'
import { productFaq } from '../structured-data'
import AppStoreBadge from './AppStoreBadge.vue'
import AppStoreQr from './AppStoreQr.vue'
import BetaFeedbackGroup from './BetaFeedbackGroup.vue'

const docs = [
  { icon: '🧭', title: '使用指南', text: '从选食材到打开做法，新手三分钟上手', link: '/guide/getting-started' },
  { icon: '💬', title: '一句话找菜', text: '用大白话描述手头食材、口味与忌口', link: '/guide/sentence-input' },
  { icon: '🛠️', title: '开发文档', text: 'CLI、MCP 工具、菜谱数据与原生客户端', link: '/dev/cli' },
  { icon: '📝', title: '方案记录', text: '了解云食册产品演进背后的每一次取舍', link: '/decisions/' },
]
</script>

<template>
  <section id="download" class="download-section" aria-labelledby="download-title" data-reveal>
    <span id="availability" class="sr-anchor" aria-hidden="true" />
    <div class="download-spotlight">
      <div class="spotlight-copy">
        <div class="app-identity">
          <img class="app-squircle" src="/brand/cook-favicon.svg" width="56" height="56" alt="">
          <div>
            <span class="live-badge">
              <span class="live-dot" aria-hidden="true" />
              App Store 正式上架 · v{{ iosVersion }}
            </span>
            <p class="app-subtitle">
              云食册 · 原食用手册
            </p>
          </div>
        </div>

        <h2 id="download-title" class="section-title">
          今晚这顿饭，从云食册开始。
        </h2>
        <p class="spotlight-lead">
          支持 iPhone 与 iPad（iOS 17 及以上），免费下载、免登录即用。无论在回家路上先翻菜单，还是在厨房里跟着步骤慢慢做，这盏小灯都随时为你亮着。
        </p>

        <div class="spotlight-actions">
          <AppStoreBadge size="lg" />
          <a class="web-fallback-btn vp-raw" :href="links.web" target="_self">
            <span>打开网页版</span>
            <span aria-hidden="true">↗</span>
          </a>
        </div>
      </div>

      <div class="spotlight-qr">
        <AppStoreQr />
      </div>
    </div>
  </section>

  <section class="support-section" aria-label="常见问题与社区交流" data-reveal style="--reveal-delay: 80ms">
    <div class="faq">
      <h3 class="faq-title">
        下载前，你可能想了解
      </h3>
      <div class="faq-list">
        <details v-for="(item, index) in productFaq" :key="item.question" class="faq-item" :open="index === 0">
          <summary>
            <span>{{ item.question }}</span>
            <svg class="faq-chevron" viewBox="0 0 20 20" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="m6 8 4 4 4-4" />
            </svg>
          </summary>
          <p>{{ item.answer }}</p>
        </details>
      </div>
    </div>

    <div class="community-stack">
      <BetaFeedbackGroup />
      <section id="contact" class="contact-card" aria-labelledby="contact-title">
        <h2 id="contact-title" class="contact-title">
          有建议，或者需要帮助？
        </h2>
        <p>产品问题、菜谱纠错、隐私与数据删除请求，都可以随时联系开发者。反馈时欢迎附上版本号与截图。</p>
        <div class="contact-links">
          <a href="mailto:me@yunyoujun.cn">me@yunyoujun.cn</a>
          <a :href="links.support" target="_blank" rel="noopener noreferrer">前往云乐坊支持中心 ↗</a>
        </div>
      </section>
    </div>
  </section>

  <div class="quiet-notes" data-reveal style="--reveal-delay: 90ms">
    <span>想抢先体验测试版？查看 <a :href="links.betaGuide">TestFlight 尝鲜说明</a></span>
    <span aria-hidden="true">·</span>
    <a href="/guide/web-domain">旧版 cook.yunle.fun 数据迁移指南</a>
    <span aria-hidden="true">·</span>
    <span>注：微信原生登录尚未接入，基础功能免登录即用</span>
  </div>

  <nav class="docs-links" aria-label="使用与开发文档" data-reveal style="--reveal-delay: 100ms">
    <a v-for="doc in docs" :key="doc.link" :href="doc.link" class="doc-card vp-raw">
      <span class="doc-icon" aria-hidden="true">{{ doc.icon }}</span>
      <div class="doc-body">
        <strong>{{ doc.title }}</strong>
        <span>{{ doc.text }}</span>
      </div>
      <span class="doc-arrow" aria-hidden="true">→</span>
    </a>
  </nav>

  <footer class="product-footer">
    <p>
      运营主体：海州区云乐坊信息技术工作室<br>
      开发者 / 开源项目版权所有者：云游君（YunYouJun）<br>
      © 2022–2026 云游君（YunYouJun） · <a href="https://beian.miit.gov.cn/" target="_blank" rel="noopener noreferrer">苏ICP备2023020936号</a>
    </p>
    <nav aria-label="法律与项目链接">
      <a href="/terms">用户协议</a>
      <a href="/privacy">iOS 隐私政策</a>
      <a href="/privacy-android">Android 隐私政策</a>
      <a :href="links.github" target="_blank" rel="noopener noreferrer">开源项目与来源说明</a>
    </nav>
  </footer>
</template>

<style scoped>
.download-section {
  position: relative;
  padding: 96px 8px 0;
}

.sr-anchor {
  position: absolute;
  top: 0;
}

.download-spotlight {
  display: grid;
  gap: 32px;
  align-items: center;
  padding: clamp(28px, 4.5vw, 48px);
  border-radius: 32px;
  border: 1px solid var(--cook-glass-border);
  background:
    radial-gradient(64% 90% at 92% 14%, rgba(250, 204, 21, 0.2), transparent 65%),
    radial-gradient(60% 80% at 10% 90%, rgba(249, 115, 22, 0.1), transparent 70%), var(--cook-surface-elevated);
  box-shadow:
    0 32px 68px -32px rgba(48, 36, 20, 0.22),
    var(--cook-glass-shadow);
}

.dark .download-spotlight {
  background:
    radial-gradient(64% 90% at 92% 14%, rgba(251, 146, 60, 0.14), transparent 65%),
    radial-gradient(60% 80% at 10% 90%, rgba(56, 189, 248, 0.14), transparent 70%), var(--cook-surface-elevated);
}

@media (min-width: 920px) {
  .download-spotlight {
    grid-template-columns: minmax(0, 1.35fr) auto;
    gap: 48px;
  }
}

.app-identity {
  display: flex;
  align-items: center;
  gap: 14px;
}

.app-squircle {
  width: 56px;
  height: 56px;
  border-radius: 15px;
  box-shadow: 0 12px 24px -10px rgba(10, 122, 90, 0.45);
}

.live-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 4px 11px;
  border-radius: 999px;
  background: var(--cook-leaf-soft);
  color: var(--cook-leaf);
  font-size: 12px;
  font-weight: 650;
}

.live-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}

.app-subtitle {
  margin-top: 5px;
  color: var(--cook-hero-muted);
  font-size: 14px;
  font-weight: 500;
}

.spotlight-lead {
  margin-top: 16px;
  max-width: 42em;
  color: var(--cook-hero-muted);
  font-size: 16px;
  line-height: 1.8;
  text-wrap: pretty;
}

.spotlight-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
  margin-top: 28px;
}

.web-fallback-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 56px;
  padding: 0 22px;
  border-radius: 16px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
  color: var(--cook-hero-text);
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
  transition:
    transform 0.22s ease,
    border-color 0.22s ease,
    color 0.22s ease;
}

.web-fallback-btn:hover {
  transform: translateY(-2px);
  border-color: var(--cook-leaf);
  color: var(--cook-leaf);
}

.spotlight-qr {
  justify-self: center;
}

@media (max-width: 767px) {
  .spotlight-qr {
    display: none;
  }
  .spotlight-actions {
    display: grid;
    grid-template-columns: 1fr;
    width: 100%;
  }
  .spotlight-actions :deep(.app-store-badge),
  .web-fallback-btn {
    width: 100%;
    justify-content: center;
  }
}

/* Support & FAQ */
.support-section {
  display: grid;
  gap: 32px;
  padding: 84px 8px 0;
}

@media (min-width: 920px) {
  .support-section {
    grid-template-columns: 1.15fr 0.95fr;
    gap: 44px;
    align-items: start;
  }
}

.faq-title {
  margin: 0 0 18px;
  color: var(--cook-hero-text);
  font-family: var(--cook-font-display);
  font-size: 24px;
  font-weight: 700;
}

.faq-list {
  display: grid;
  gap: 12px;
}

.faq-item {
  padding: 16px 20px;
  border-radius: 18px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
  transition:
    border-color 0.2s ease,
    background-color 0.2s ease;
}

.faq-item[open] {
  background: var(--cook-surface-elevated);
  border-color: rgba(224, 159, 31, 0.45);
}

.faq-item summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  cursor: pointer;
  list-style: none;
  color: var(--cook-hero-text);
  font-size: 16px;
  font-weight: 650;
  line-height: 1.45;
}

.faq-item summary::-webkit-details-marker {
  display: none;
}

.faq-chevron {
  flex-shrink: 0;
  color: var(--cook-hero-muted);
  transition: transform 0.25s ease;
}

.faq-item[open] .faq-chevron {
  transform: rotate(180deg);
  color: var(--cook-flame-2);
}

.faq-item p {
  margin-top: 12px;
  color: var(--cook-hero-muted);
  font-size: 14px;
  line-height: 1.78;
  text-wrap: pretty;
}

.community-stack {
  display: grid;
  gap: 16px;
}

.contact-card {
  padding: 24px 22px;
  border-radius: 22px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
}

.contact-title {
  color: var(--cook-hero-text);
  font-family: var(--cook-font-display);
  font-size: 20px;
  font-weight: 700;
}

.contact-card p {
  margin-top: 10px;
  color: var(--cook-hero-muted);
  font-size: 14px;
  line-height: 1.75;
}

.contact-links {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
  margin-top: 16px;
}

.contact-links a {
  color: var(--cook-leaf);
  font-size: 14px;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 4px;
}

.quiet-notes {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
  margin: 40px 8px 0;
  color: var(--cook-hero-muted);
  font-size: 12px;
  line-height: 1.65;
}

.quiet-notes a {
  color: var(--cook-leaf);
  text-decoration: underline;
  text-underline-offset: 4px;
}

/* Docs Quick Grid */
.docs-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
  gap: 14px;
  margin: 36px 8px 0;
}

.doc-card {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 18px;
  border-radius: 20px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
  text-decoration: none;
  transition:
    transform 0.22s ease,
    border-color 0.22s ease,
    background-color 0.22s ease;
}

.doc-card:hover {
  transform: translateY(-2px);
  border-color: var(--cook-leaf);
  background: var(--cook-surface-elevated);
}

.doc-icon {
  display: grid;
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  place-items: center;
  border-radius: 12px;
  background: var(--cook-action-secondary);
  font-size: 18px;
}

.doc-body {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.doc-body strong {
  color: var(--cook-hero-text);
  font-size: 16px;
  font-weight: 650;
}

.doc-body span {
  color: var(--cook-hero-muted);
  font-size: 12px;
  line-height: 1.5;
}

.doc-arrow {
  margin-left: auto;
  color: var(--cook-hero-muted);
  transition:
    transform 0.2s ease,
    color 0.2s ease;
}

.doc-card:hover .doc-arrow {
  transform: translateX(3px);
  color: var(--cook-leaf);
}

/* Footer */
.product-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 24px;
  margin-top: 56px;
  padding: 28px 8px 44px;
  border-top: 1px solid var(--cook-glass-border);
  color: var(--cook-hero-muted);
  font-size: 14px;
  line-height: 1.85;
}

.product-footer nav {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 22px;
  align-items: center;
}

.product-footer a {
  color: inherit;
  text-decoration: underline;
  text-underline-offset: 4px;
}

.product-footer a:hover {
  color: var(--cook-leaf);
}

@media (max-width: 639px) {
  .download-section,
  .support-section {
    padding: 68px 0 0;
  }
  .quiet-notes {
    margin: 32px 0 0;
  }
  .docs-links {
    margin: 28px 0 0;
  }
  .product-footer {
    padding-inline: 0;
  }
}

@media (prefers-reduced-motion: reduce) {
  .web-fallback-btn,
  .faq-chevron,
  .doc-card,
  .doc-arrow {
    transition: none;
  }
  .web-fallback-btn:hover,
  .doc-card:hover {
    transform: none;
  }
}
</style>
