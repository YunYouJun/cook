<script setup lang="ts">
import PhoneDemo from './PhoneDemo.vue'

const screens = [
  { file: '2-cook-selected', alt: '做菜页：已选土豆、番茄和鸡蛋，底部按钮显示能做 239 道' },
  { file: '4-results', alt: '能做的菜列表：6 阶番茄炒蛋、番茄炒蛋、酱土豆' },
  { file: '5-detail', alt: '番茄炒蛋详情：已有食材、做法和标签、去 B 站看做法' },
  { file: '8-random', alt: '今天吃什么：随机抽到胡萝卜炒肉' },
]

const appOnly = [
  { emoji: '📴', title: '离线也能翻', text: '菜谱数据和已内置的文字做法可离线使用；配图和外部视频需要网络。' },
  { emoji: '📒', title: '私人菜谱', text: '把自己的拿手菜记进来，可以和网页版互相导入导出。' },
  { emoji: '☁️', title: 'iCloud 同步', text: '支持同一 Apple 账号下的数据同步；仍需双设备验收，请保留独立备份。' },
  { emoji: '📐', title: 'iPad 和横屏', text: '宽屏时左边选食材、右边出结果，深色模式和大字号也照顾到了。' },
]
</script>

<template>
  <section class="screens" aria-labelledby="screens-title">
    <h2 id="screens-title" class="section-title">
      装进口袋的食用手册
    </h2>
    <p class="section-lead">
      还是熟悉的找菜方式，换成了 iPhone 上顺手的样子。
    </p>
    <PhoneDemo class="demo-block" />
    <figure class="shots">
      <ul class="shot-row">
        <li v-for="screen in screens" :key="screen.file" class="shot">
          <img class="shot-light" :src="`/ios/light-${screen.file}.webp`" :alt="screen.alt" width="640" height="1391" loading="lazy" decoding="async">
          <img class="shot-dark" :src="`/ios/dark-${screen.file}.webp`" :alt="screen.alt" width="640" height="1391" loading="lazy" decoding="async">
        </li>
      </ul>
      <figcaption>iOS 开发版本截图，实际界面可能随版本调整</figcaption>
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
</template>
