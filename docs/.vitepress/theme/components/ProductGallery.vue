<script setup lang="ts">
import PhoneDemo from './PhoneDemo.vue'

const screens = [
  { file: '2-cook-selected', alt: '做菜页：已选土豆、番茄和鸡蛋，底部按钮显示能做 239 道' },
  { file: '4-results', alt: '能做的菜列表：6 阶番茄炒蛋、番茄炒蛋、酱土豆' },
  { file: '5-detail', alt: '番茄炒蛋详情：已有食材、做法和标签、去 B 站看做法' },
  { file: '8-random', alt: '今天吃什么：随机抽到胡萝卜炒肉' },
]

const appOnly = [
  { emoji: '📴', title: '本地菜谱与正文', text: 'iOS 版的本地菜谱与内置文字做法可离线阅读，配图和外部视频需要网络。' },
  { emoji: '📒', title: '私人菜谱', text: '记下自己的拿手菜，主动导入、导出文件保留备份。私人菜谱不会自动公开。' },
  { emoji: '☁️', title: '数据与备份', text: '记录保存在本机。iCloud 同步仍待生产环境与双设备验收，不作为已验证能力承诺；与网页版不会自动同步。' },
  { emoji: '📐', title: 'iPad 和横屏', text: '宽屏时左边选食材、右边出结果，深色模式和大字号也照顾到了。' },
]
</script>

<template>
  <div>
    <section id="screens" class="screens" aria-labelledby="screens-title">
      <h2 id="screens-title" class="section-title">
        装进口袋的食用手册
      </h2>
      <p class="section-lead">
        还是熟悉的找菜方式，换成了 iPhone 上顺手的样子。
      </p>
      <p class="simulation-label">
        网页交互模拟 · 使用真实菜谱数据，点击章节或播放按钮开始。
      </p>
      <PhoneDemo class="demo-block" />
      <figure class="shots">
        <ul class="shot-row" tabindex="0" aria-label="iOS 开发版本真实截图，可横向滚动">
          <li v-for="screen in screens" :key="screen.file" class="shot">
            <img class="shot-light" :src="`/ios/light-${screen.file}.webp`" :alt="screen.alt" width="640" height="1392" loading="lazy" decoding="async">
            <img class="shot-dark" :src="`/ios/dark-${screen.file}.webp`" :alt="screen.alt" width="640" height="1392" loading="lazy" decoding="async">
          </li>
        </ul>
        <figcaption>iOS 开发版本真实截图 · 界面可能随版本调整，不代表已提供下载</figcaption>
      </figure>
    </section>

    <section class="app-only" aria-labelledby="app-only-title">
      <h2 id="app-only-title" class="section-title">
        App 里多了这些
      </h2>
      <dl class="feature-list">
        <div v-for="feature in appOnly" :key="feature.title" class="feature">
          <dt><span class="feature-emoji" aria-hidden="true">{{ feature.emoji }}</span>{{ feature.title }}</dt>
          <dd>{{ feature.text }}</dd>
        </div>
      </dl>
    </section>
  </div>
</template>

<style scoped>
.simulation-label {
  margin-top: 24px;
  color: var(--vp-c-text-2);
  font-size: 14px;
}
.shot-row:focus-visible {
  outline: 3px solid var(--vp-c-brand-1);
  outline-offset: 4px;
}

.demo-block {
  margin-top: 32px;
}

/* 演示下面一排真机截图，证明不是做出来的效果图 */
.shots {
  margin: 72px 0 0;
}

.shot-row {
  display: grid;
  grid-auto-flow: column;
  grid-auto-columns: minmax(150px, 1fr);
  gap: 16px;
  margin: 0;
  padding: 0 0 12px;
  list-style: none;
  overflow-x: auto;
  scroll-snap-type: x mandatory;
}

@media (min-width: 960px) {
  .shot-row {
    max-width: 760px;
    margin: 0 auto;
    padding: 0;
    overflow: visible;
  }
}

.shot {
  aspect-ratio: 640 / 1392;
  padding: 4px;
  border-radius: 26px;
  background: #0f172a;
  scroll-snap-align: start;
}

.dark .shot {
  background: #1e293b;
}

.shot img {
  width: 100%;
  height: 100%;
  border-radius: 22px;
  object-fit: cover;
}

.shot-dark,
.dark .shot-light {
  display: none;
}

.dark .shot-dark {
  display: block;
}

.shots figcaption {
  margin-top: 12px;
  font-size: 13px;
  text-align: center;
  color: var(--vp-c-text-3);
}

.feature-list {
  display: grid;
  gap: 32px 48px;
  margin: 40px 0 0;
}

@media (min-width: 640px) {
  .feature-list {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 960px) {
  .feature-list {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

.feature dt {
  display: flex;
  align-items: center;
  gap: 12px;
  font-family: var(--cook-font-display);
  font-size: 22px;
}

.feature-emoji {
  display: grid;
  place-items: center;
  width: 44px;
  height: 44px;
  border-radius: 14px;
  background: var(--mist);
  font-family: initial;
  font-size: 22px;
}

.feature dd {
  margin: 10px 0 0;
  font-size: 15px;
  line-height: 1.7;
  color: var(--vp-c-text-2);
}
</style>
