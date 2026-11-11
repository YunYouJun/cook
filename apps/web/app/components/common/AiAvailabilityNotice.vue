<script setup lang="ts">
import { links } from '~/constants'
</script>

<template>
  <div class="ai-availability">
    <p>网页版通过本地词表理解整句、筛选已有菜谱，不调用 AI 模型。</p>
    <p>
      模型能力集中在云食册 App（目前为 iOS）：智能找菜和基于原文的步骤讲解，均由你主动发起。
      可选择云乐坊会员服务或自带 DeepSeek API Key；自带 Key 使用个人账户额度。
    </p>
    <p>
      步骤讲解目前仅向已有内部测试成员开放，当前公开 TestFlight 邀请尚不包含此功能。
      具体可用能力以所安装版本为准。
    </p>
    <a :href="`${links.iosBetaGuide}#ai`" target="_blank" rel="noopener noreferrer">查看 App 测试范围与安装说明</a>
  </div>
</template>

<style scoped>
.ai-availability {
  color: var(--ion-text-color, #1c1c1e);
  font-size: 14px;
  line-height: 1.7;
}
.ai-availability p {
  margin: 0 0 12px;
}
.ai-availability a {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--ion-color-primary);
}
.ai-availability a:focus-visible {
  outline: 2px solid var(--ion-color-primary);
  outline-offset: 3px;
}
</style>
