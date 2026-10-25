<script setup lang="ts">
import { iosVersion, links } from '../constants'

const docs = [
  { title: '使用指南', text: '从选食材到打开做法', link: '/guide/getting-started' },
  { title: '一句话找菜', text: '描述食材、口味与忌口', link: '/guide/sentence-input' },
  { title: '开发文档', text: 'CLI、菜谱数据与移动应用', link: '/dev/cli' },
  { title: '方案记录', text: '了解产品背后的决定', link: '/decisions/' },
]
</script>

<template>
  <section id="availability" class="services" aria-labelledby="availability-title">
    <div>
      <h2 id="availability-title" class="section-title">
        现在，先做一顿饭
      </h2>
      <p class="section-lead">
        食用手册网页版已经可以使用。云食册 iOS {{ iosVersion }} 正在准备发布，需要 iOS 17 / iPadOS 17 或更新版本。
      </p>
      <p><strong>iOS 版尚未在 App Store 上架，暂无公开 TestFlight 安装邀请。</strong>首批体验报名已开放，报名不代表获得安装资格；审核与验收完成后再分批邀请。</p>
      <div class="service-actions">
        <a class="service-button vp-raw" :href="links.web" target="_self">打开食用手册网页版</a>
        <a class="service-link" :href="links.signup">了解 iOS 体验报名</a>
      </div>
      <p class="service-note">
        以前在 cook.yunle.fun 用过网页版？<a class="service-link" href="/guide/web-domain">查看旧版数据导出与迁移说明</a>。
      </p>
      <p class="service-note">
        微信原生 SDK 与微信登录尚未接入，仅为后续计划。可用功能与登录方式以实际版本及后续公告为准。
      </p>
    </div>
    <div class="faq">
      <h3 class="faq-title">
        使用前，你可能想知道
      </h3>
      <details open>
        <summary>云食册和食用手册是什么关系？</summary>
        <p>云食册是食用手册的原生 iOS 应用，沿用根据食材找菜的思路。网页版继续使用「食用手册」这个名字；iOS 版增加私人菜谱、冰箱与本地临期提醒等功能。</p>
      </details>
      <details>
        <summary>需要登录吗？数据会自动同步吗？</summary>
        <p>基础选食材、找菜和查看做法不要求注册或登录。云乐坊账号与 Apple 账户相互独立，iOS 与网页版目前不会自动同步。请使用相应版本的导出功能定期备份；iCloud 同步仍待生产环境和双设备验收。</p>
      </details>
      <details>
        <summary>没有网络时还能用吗？</summary>
        <p>iOS 版的本地菜谱与内置图文正文可离线阅读；配图、外部视频、账号服务与 iCloud 同步需要网络。网页版不承诺完整离线可用。</p>
      </details>
      <details>
        <summary>食材提醒可以判断是否安全食用吗？</summary>
        <p>不能。食材日期和临期提醒只用于日常安排，不是食品安全检测。请结合包装说明、实际储存条件与食材状态判断；有过敏、特殊饮食或健康问题时，请咨询专业人士。</p>
      </details>
    </div>
  </section>

  <nav class="docs-links" aria-label="使用与开发文档">
    <a v-for="doc in docs" :key="doc.link" :href="doc.link">
      <strong>{{ doc.title }}</strong>
      <span>{{ doc.text }}</span>
    </a>
  </nav>

  <section id="contact" class="contact" aria-labelledby="contact-title">
    <div>
      <h2 id="contact-title" class="section-title">
        有建议，或者需要帮助？
      </h2>
      <p>产品问题、菜谱纠错、隐私与数据删除请求，都可以联系开发者。反馈时请写下版本、遇到的问题和必要的截图。</p>
    </div>
    <div class="contact-links">
      <a href="mailto:me@yunyoujun.cn">me@yunyoujun.cn</a>
      <a :href="links.support">前往云乐坊支持中心</a>
    </div>
  </section>

  <div class="product-footer">
    <p>开发者 / 版权所有者：云游君（YunYouJun）<br>© 2022 云游君（YunYouJun）</p>
    <nav aria-label="法律与项目链接">
      <a href="/terms">用户协议</a>
      <a href="/privacy">iOS 隐私政策</a>
      <a :href="links.github">开源项目与来源说明</a>
    </nav>
  </div>
</template>

<style scoped>
.services {
  display: grid;
  gap: 48px;
  padding: 96px 8px 0;
}
.services p,
.contact p {
  max-width: 44em;
  line-height: 1.85;
  margin-top: 20px;
}
.service-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 20px;
  margin-top: 28px;
}
.service-button {
  display: inline-flex;
  align-items: center;
  min-height: 48px;
  padding: 8px 24px;
  border-radius: 999px;
  background: #2563eb;
  color: #fff;
  font-weight: 600;
}
.service-link,
.contact-links a {
  color: var(--vp-c-brand-1);
  text-decoration: underline;
  text-underline-offset: 4px;
}
.service-note {
  color: var(--vp-c-text-2);
  font-size: 14px;
}
.faq-title {
  font-family: var(--cook-font-display);
  font-size: 24px;
  margin: 0 0 16px;
}
.faq details {
  border-bottom: 1px solid var(--vp-c-divider);
  padding: 16px 0;
}
.faq summary {
  cursor: pointer;
  font-weight: 600;
  min-height: 28px;
}
.faq p {
  color: var(--vp-c-text-2);
  font-size: 15px;
  margin-top: 12px;
}
.docs-links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 20px 32px;
  margin: 80px 8px 0;
  padding: 32px 0;
  border-block: 1px solid var(--vp-c-divider);
}
.docs-links a {
  display: grid;
  gap: 6px;
  padding: 8px 0;
}
.docs-links strong {
  font-size: 18px;
}
.docs-links span {
  font-size: 14px;
  color: var(--vp-c-text-2);
}
.docs-links a:hover strong {
  color: var(--vp-c-brand-1);
}
.contact {
  display: grid;
  gap: 24px;
  padding: 72px 8px 56px;
}
.contact-links {
  display: flex;
  flex-direction: column;
  gap: 16px;
  justify-content: center;
}
.product-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 8px 40px;
  border-top: 1px solid var(--vp-c-divider);
  color: var(--vp-c-text-2);
  font-size: 14px;
  line-height: 1.85;
}
.product-footer nav {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: center;
}
.product-footer a {
  text-decoration: underline;
  text-underline-offset: 4px;
}
@media (min-width: 960px) {
  .services {
    grid-template-columns: 1.15fr 1fr;
    gap: 64px;
  }
  .contact {
    grid-template-columns: 1.5fr 1fr;
    gap: 64px;
  }
}
</style>
