<script setup lang="ts">
import { links, testflightUrl } from '../constants'
</script>

<template>
  <section id="beta" class="beta" aria-labelledby="beta-title">
    <div class="beta-main">
      <h2 id="beta-title" class="section-title">
        报名体验
      </h2>
      <ol class="beta-steps">
        <li>
          <strong>在 iPhone 上安装 TestFlight</strong>
          <span>苹果官方的测试工具，<a :href="links.testflightApp" target="_blank" rel="noopener">在 App Store 免费下载</a>。</span>
        </li>
        <li>
          <strong>报名并等待安装邀请</strong>
          <span>先填写体验报名；Apple 外测审核和必要验收完成后再分批邀请。收到邀请后，通过 TestFlight 接受并安装。</span>
        </li>
        <li>
          <strong>用起来，告诉我们哪里不好用</strong>
          <span>在 App 的「我的 → 意见反馈」留言，或者去<a :href="links.feedbackBoard" target="_blank" rel="noopener">需求墙</a>投票。</span>
        </li>
      </ol>
      <div class="beta-actions">
        <a v-if="testflightUrl" class="btn btn-sky" :href="testflightUrl" target="_blank" rel="noopener">加入 TestFlight 测试</a>
        <template v-else>
          <a class="btn btn-sky" :href="links.signup">填写体验报名</a>
          <span class="beta-pending">当前仅开放报名，安装邀请尚未开放；报名不代表已获得安装资格。</span>
        </template>
      </div>
    </div>
    <aside class="beta-notes" aria-label="测试须知">
      <h3>测试须知</h3>
      <ul>
        <li>测试版本有使用期限，有新版本时 TestFlight 会提醒更新。</li>
        <li>iCloud 同步还在验收，建议隔一阵在「我的」里导出一份收藏和浏览记录。</li>
        <li>iOS 版尚未在 App Store 上架，公开下载时间以发布公告为准。</li>
        <li>网页版照常免费使用，两边的收藏可以互相导入。</li>
      </ul>
    </aside>
  </section>
</template>
