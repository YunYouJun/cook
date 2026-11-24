<script setup lang="ts">
const qrPath = 'M4,4h7v1h-7zM14,4h1v1h-1zM18,4h1v1h-1zM21,4h3v1h-3zM26,4h7v1h-7zM4,5h1v1h-1zM10,5h1v1h-1zM13,5h3v1h-3zM18,5h4v1h-4zM24,5h1v1h-1zM26,5h1v1h-1zM32,5h1v1h-1zM4,6h1v1h-1zM6,6h3v1h-3zM10,6h1v1h-1zM12,6h1v1h-1zM14,6h2v1h-2zM18,6h2v1h-2zM24,6h1v1h-1zM26,6h1v1h-1zM28,6h3v1h-3zM32,6h1v1h-1zM4,7h1v1h-1zM6,7h3v1h-3zM10,7h1v1h-1zM12,7h1v1h-1zM15,7h1v1h-1zM17,7h1v1h-1zM20,7h2v1h-2zM23,7h1v1h-1zM26,7h1v1h-1zM28,7h3v1h-3zM32,7h1v1h-1zM4,8h1v1h-1zM6,8h3v1h-3zM10,8h1v1h-1zM12,8h2v1h-2zM15,8h2v1h-2zM18,8h1v1h-1zM22,8h3v1h-3zM26,8h1v1h-1zM28,8h3v1h-3zM32,8h1v1h-1zM4,9h1v1h-1zM10,9h1v1h-1zM12,9h5v1h-5zM18,9h2v1h-2zM22,9h1v1h-1zM24,9h1v1h-1zM26,9h1v1h-1zM32,9h1v1h-1zM4,10h7v1h-7zM12,10h1v1h-1zM14,10h1v1h-1zM16,10h1v1h-1zM18,10h1v1h-1zM20,10h1v1h-1zM22,10h1v1h-1zM24,10h1v1h-1zM26,10h7v1h-7zM12,11h2v1h-2zM15,11h2v1h-2zM19,11h1v1h-1zM23,11h2v1h-2zM4,12h1v1h-1zM6,12h5v1h-5zM13,12h2v1h-2zM16,12h2v1h-2zM20,12h2v1h-2zM23,12h1v1h-1zM26,12h5v1h-5zM8,13h1v1h-1zM12,13h1v1h-1zM15,13h2v1h-2zM18,13h1v1h-1zM23,13h4v1h-4zM28,13h1v1h-1zM32,13h1v1h-1zM5,14h1v1h-1zM7,14h1v1h-1zM10,14h1v1h-1zM13,14h1v1h-1zM15,14h1v1h-1zM17,14h1v1h-1zM19,14h2v1h-2zM22,14h1v1h-1zM24,14h1v1h-1zM26,14h2v1h-2zM4,15h1v1h-1zM6,15h1v1h-1zM12,15h4v1h-4zM19,15h2v1h-2zM22,15h2v1h-2zM25,15h2v1h-2zM28,15h2v1h-2zM31,15h1v1h-1zM9,16h3v1h-3zM13,16h1v1h-1zM15,16h1v1h-1zM19,16h3v1h-3zM27,16h1v1h-1zM29,16h2v1h-2zM5,17h4v1h-4zM11,17h2v1h-2zM17,17h1v1h-1zM23,17h6v1h-6zM32,17h1v1h-1zM5,18h2v1h-2zM10,18h4v1h-4zM15,18h1v1h-1zM17,18h3v1h-3zM22,18h1v1h-1zM25,18h1v1h-1zM28,18h3v1h-3zM6,19h2v1h-2zM11,19h1v1h-1zM14,19h1v1h-1zM16,19h2v1h-2zM19,19h2v1h-2zM22,19h3v1h-3zM28,19h1v1h-1zM31,19h1v1h-1zM4,20h1v1h-1zM6,20h1v1h-1zM8,20h4v1h-4zM13,20h1v1h-1zM16,20h1v1h-1zM18,20h6v1h-6zM27,20h1v1h-1zM29,20h2v1h-2zM4,21h1v1h-1zM6,21h1v1h-1zM8,21h2v1h-2zM12,21h1v1h-1zM14,21h1v1h-1zM21,21h3v1h-3zM26,21h3v1h-3zM30,21h1v1h-1zM32,21h1v1h-1zM4,22h1v1h-1zM7,22h2v1h-2zM10,22h1v1h-1zM13,22h3v1h-3zM19,22h1v1h-1zM21,22h1v1h-1zM24,22h2v1h-2zM28,22h1v1h-1zM30,22h1v1h-1zM4,23h1v1h-1zM6,23h1v1h-1zM9,23h1v1h-1zM11,23h2v1h-2zM14,23h1v1h-1zM16,23h1v1h-1zM18,23h1v1h-1zM24,23h2v1h-2zM28,23h1v1h-1zM31,23h1v1h-1zM4,24h1v1h-1zM6,24h1v1h-1zM8,24h3v1h-3zM14,24h3v1h-3zM19,24h3v1h-3zM24,24h5v1h-5zM30,24h3v1h-3zM12,25h1v1h-1zM15,25h4v1h-4zM21,25h2v1h-2zM24,25h1v1h-1zM28,25h5v1h-5zM4,26h7v1h-7zM14,26h1v1h-1zM16,26h4v1h-4zM22,26h3v1h-3zM26,26h1v1h-1zM28,26h3v1h-3zM4,27h1v1h-1zM10,27h1v1h-1zM12,27h2v1h-2zM15,27h3v1h-3zM19,27h1v1h-1zM23,27h2v1h-2zM28,27h1v1h-1zM32,27h1v1h-1zM4,28h1v1h-1zM6,28h3v1h-3zM10,28h1v1h-1zM12,28h1v1h-1zM16,28h1v1h-1zM18,28h1v1h-1zM20,28h2v1h-2zM24,28h5v1h-5zM30,28h1v1h-1zM4,29h1v1h-1zM6,29h3v1h-3zM10,29h1v1h-1zM12,29h1v1h-1zM14,29h4v1h-4zM22,29h1v1h-1zM27,29h1v1h-1zM29,29h4v1h-4zM4,30h1v1h-1zM6,30h3v1h-3zM10,30h1v1h-1zM12,30h1v1h-1zM14,30h6v1h-6zM23,30h2v1h-2zM26,30h6v1h-6zM4,31h1v1h-1zM10,31h1v1h-1zM16,31h2v1h-2zM20,31h1v1h-1zM22,31h1v1h-1zM24,31h2v1h-2zM27,31h3v1h-3zM31,31h1v1h-1zM4,32h7v1h-7zM12,32h2v1h-2zM17,32h7v1h-7zM25,32h1v1h-1zM28,32h1v1h-1zM30,32h1v1h-1z'
</script>

<template>
  <div class="qr-card vp-raw">
    <div class="qr-frame">
      <svg
        class="qr-svg"
        viewBox="0 0 37 37"
        width="160"
        height="160"
        shape-rendering="crispEdges"
        role="img"
        aria-label="扫描二维码前往 App Store 下载云食册 iOS 版"
      >
        <rect width="37" height="37" fill="#ffffff" />
        <path :d="qrPath" fill="#0f172a" />
      </svg>
    </div>
    <p class="qr-title">
      iPhone 相机扫码，直达 App Store
    </p>
    <p class="qr-sub">
      免费下载 · 支持 iPhone / iPad
    </p>
  </div>
</template>

<style scoped>
.qr-card {
  display: grid;
  justify-items: center;
  text-align: center;
  padding: 20px 22px 18px;
  border-radius: 24px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-surface-elevated);
  box-shadow: var(--cook-glass-shadow);
  transition: transform 0.25s cubic-bezier(0.22, 1, 0.36, 1);
}

.qr-card:hover {
  transform: translateY(-2px);
}

.qr-frame {
  padding: 8px;
  border-radius: 18px;
  background: #ffffff;
  box-shadow:
    inset 0 0 0 1px rgba(15, 23, 42, 0.08),
    0 10px 24px -14px rgba(15, 23, 42, 0.24);
  transition: transform 0.25s cubic-bezier(0.22, 1, 0.36, 1);
}

.qr-card:hover .qr-frame {
  transform: scale(1.03);
}

.qr-svg {
  display: block;
  width: 152px;
  height: 152px;
  border-radius: 10px;
}

.qr-title {
  margin-top: 14px;
  color: var(--cook-hero-text);
  font-size: 14px;
  font-weight: 650;
  line-height: 1.4;
}

.qr-sub {
  margin-top: 3px;
  color: var(--cook-hero-muted);
  font-size: 12px;
  line-height: 1.4;
}

@media (prefers-reduced-motion: reduce) {
  .qr-card,
  .qr-frame {
    transition: none;
  }
  .qr-card:hover,
  .qr-card:hover .qr-frame {
    transform: none;
  }
}
</style>
