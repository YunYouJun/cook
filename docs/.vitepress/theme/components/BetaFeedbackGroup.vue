<script setup lang="ts">
const inviteUrl = 'https://qm.qq.com/q/fVHcR8sMQE'
</script>

<template>
  <div class="beta-group vp-raw">
    <div>
      <div class="beta-group-name">
        云食册 APP 内测反馈群
      </div>
      <div class="beta-group-number">
        QQ 群号 <strong>1108267397</strong>
      </div>
      <a class="beta-group-link" :href="inviteUrl" target="_blank" rel="noopener noreferrer">加入 QQ 群</a>
    </div>
    <img
      class="beta-group-qr"
      src="/landing/qq-beta-group.png"
      alt="用 QQ 扫码加入云食册 APP 内测反馈群"
      width="112"
      height="112"
      loading="lazy"
      decoding="async"
    >
  </div>
</template>

<style scoped>
.beta-group {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  margin-top: 24px;
  padding: 16px;
  border: 1px solid var(--vp-c-divider);
  border-radius: var(--cook-control-radius, 12px);
  background: var(--vp-c-bg-soft);
}
.beta-group-name {
  font-weight: 650;
  line-height: 1.45;
}
.beta-group-number {
  margin-top: 4px;
  color: var(--vp-c-text-2);
  font-size: 14px;
}
.beta-group-link {
  display: inline-flex;
  min-height: 44px;
  align-items: center;
  color: var(--vp-c-brand-1);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 4px;
}
.beta-group-qr {
  display: block;
  margin: 0;
  border-radius: 8px;
}
@media (max-width: 359px) {
  .beta-group {
    grid-template-columns: 1fr;
  }
}
</style>
