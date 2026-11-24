<script setup lang="ts">
const inviteUrl = 'https://qm.qq.com/q/fVHcR8sMQE'
</script>

<template>
  <div class="beta-group vp-raw">
    <div>
      <span class="beta-group-tag">厨友交流</span>
      <div class="beta-group-name">
        云食册交流与反馈群
      </div>
      <div class="beta-group-number">
        QQ 群号 <strong>1108267397</strong>
      </div>
      <p class="beta-group-desc">
        日常求菜、体验建议或晒晒今晚做的饭，欢迎来群里坐坐。
      </p>
      <a class="beta-group-link" :href="inviteUrl" target="_blank" rel="noopener noreferrer">
        <span>加入 QQ 群</span>
        <span aria-hidden="true">↗</span>
      </a>
    </div>
    <img
      class="beta-group-qr"
      src="/landing/qq-beta-group.png"
      alt="用 QQ 扫码加入云食册交流与反馈群"
      width="104"
      height="104"
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
  gap: 16px;
  padding: 22px;
  border: 1px solid var(--cook-glass-border);
  border-radius: 22px;
  background: var(--cook-surface-elevated);
  box-shadow: var(--cook-glass-shadow);
}

.beta-group-tag {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--cook-leaf-soft);
  color: var(--cook-leaf);
  font-size: 12px;
  font-weight: 650;
}

.beta-group-name {
  margin-top: 8px;
  color: var(--cook-hero-text);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.4;
}

.beta-group-number {
  margin-top: 4px;
  color: var(--cook-hero-muted);
  font-size: 14px;
}

.beta-group-number strong {
  color: var(--cook-hero-text);
  font-variant-numeric: tabular-nums;
}

.beta-group-desc {
  margin-top: 8px;
  color: var(--cook-hero-muted);
  font-size: 14px;
  line-height: 1.65;
}

.beta-group-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  min-height: 40px;
  margin-top: 4px;
  color: var(--cook-leaf);
  font-size: 14px;
  font-weight: 650;
  text-decoration: underline;
  text-underline-offset: 4px;
}

.beta-group-qr {
  display: block;
  margin: 0;
  padding: 4px;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 6px 16px -8px rgba(15, 23, 42, 0.25);
}

@media (max-width: 420px) {
  .beta-group {
    grid-template-columns: 1fr;
  }
}
</style>
