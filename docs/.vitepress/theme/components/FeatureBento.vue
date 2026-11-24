<script setup lang="ts">
import { computed } from 'vue'
import { total } from '../landing'

const previewIngredients = [
  { name: '番茄', src: '/models/ingredients/tomato.png' },
  { name: '鸡蛋', src: '/models/ingredients/egg.png' },
  { name: '土豆', src: '/models/ingredients/potato.png' },
  { name: '胡萝卜', src: '/models/ingredients/carrot.png' },
  { name: '豆腐', src: '/models/ingredients/tofu.png' },
  { name: '米饭', src: '/models/ingredients/rice.png' },
]

const footprintDays = [true, true, false, true, true, false, false]
const recipeCount = computed(() => total)

function onPointerMove(event: PointerEvent) {
  if (event.pointerType !== 'mouse')
    return
  const el = event.currentTarget as HTMLElement | null
  if (!el)
    return
  const rect = el.getBoundingClientRect()
  el.style.setProperty('--mx', `${event.clientX - rect.left}px`)
  el.style.setProperty('--my', `${event.clientY - rect.top}px`)
}
</script>

<template>
  <div class="bento-grid">
    <article class="bento-card is-wide is-sky" data-reveal @pointermove="onPointerMove">
      <div class="bento-copy">
        <h3>3D 云厨房与食材快选</h3>
        <p>既能像小游戏一样拨弄 3D 食材小摊、点锅开火，也能切回极速列表勾选，两套视图随时顺滑接力。</p>
      </div>
      <div class="bento-visual" aria-hidden="true">
        <div
          v-for="(item, index) in previewIngredients"
          :key="item.name"
          class="mini-ingredient"
          :style="{ '--i': index }"
        >
          <img :src="item.src" alt="" width="56" height="56" loading="lazy" decoding="async">
        </div>
      </div>
    </article>

    <article class="bento-card is-narrow" data-reveal style="--reveal-delay: 70ms" @pointermove="onPointerMove">
      <div class="bento-copy">
        <h3>厨房工作台与内置计时</h3>
        <p>图文做法、备料清单与倒计时同屏安放，边看步骤边翻炒，不用频繁擦手解锁翻页。</p>
      </div>
      <div class="mini-timer" aria-hidden="true">
        <span class="mini-timer-step">步骤 2/4 · 中火翻炒</span>
        <span class="mini-timer-clock">02:30</span>
      </div>
    </article>

    <article class="bento-card is-narrow" data-reveal style="--reveal-delay: 110ms" @pointermove="onPointerMove">
      <div class="bento-copy">
        <h3>冰箱记账与拍照录入</h3>
        <p>随手记下或拍下买回来的食材，主动开启本地提醒，在它们还新鲜时温柔叫你吃掉。</p>
      </div>
      <div class="mini-fridge" aria-hidden="true">
        <span class="fridge-pill is-warn">豆腐 · 今天到期</span>
        <span class="fridge-pill is-fresh">鸡蛋 · 还剩 26 天</span>
      </div>
    </article>

    <article class="bento-card is-wide is-leaf" data-reveal style="--reveal-delay: 150ms" @pointermove="onPointerMove">
      <div class="bento-copy">
        <h3>离线 {{ recipeCount }} 道菜谱与图文正文</h3>
        <p>内置菜谱与文字做法随 App 装进手机里，走进信号微弱的厨房或菜市场也能安心翻看；配图与外部视频跳转需联网。</p>
      </div>
      <div class="bento-pill-stack" aria-hidden="true">
        <span class="pill-chip">🍅 番茄炒蛋</span>
        <span class="pill-chip">🥔 酸辣土豆丝</span>
        <span class="pill-chip">🥬 清炒时蔬</span>
        <span class="pill-chip is-offline">✓ 离线可读</span>
      </div>
    </article>

    <article class="bento-card is-half" data-reveal style="--reveal-delay: 190ms" @pointermove="onPointerMove">
      <div class="bento-copy">
        <h3>私人菜谱 &amp; 做菜足迹</h3>
        <p>记下自己的拿手菜与家人秘方，随时导出 JSON 文件备份；点亮每一次「做过」与「学会」，攒下热腾腾的厨房日记。</p>
      </div>
      <div class="mini-footprint" aria-hidden="true">
        <div class="footprint-dots">
          <span v-for="(active, idx) in footprintDays" :key="idx" class="footprint-dot" :class="{ 'is-on': active }" />
        </div>
        <span class="footprint-label">本周下厨 4 天 · 已学会 12 道</span>
      </div>
    </article>

    <article class="bento-card is-half" data-reveal style="--reveal-delay: 230ms" @pointermove="onPointerMove">
      <div class="bento-copy">
        <h3>认真照顾每一块屏幕</h3>
        <p>iPad 宽屏支持左侧选食材、右侧实时出菜单；全面适配深色模式与系统大字号，深夜下厨与长辈使用同样舒服。</p>
      </div>
      <div class="mini-adapt" aria-hidden="true">
        <span class="adapt-chip">iPad 分屏</span>
        <span class="adapt-chip">深色模式</span>
        <span class="adapt-chip">大字号友好</span>
      </div>
    </article>
  </div>
</template>

<style scoped>
.bento-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
  margin-top: 36px;
}

@media (min-width: 680px) {
  .bento-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 980px) {
  .bento-grid {
    grid-template-columns: repeat(6, minmax(0, 1fr));
  }
  .bento-card.is-wide {
    grid-column: span 4;
  }
  .bento-card.is-narrow {
    grid-column: span 2;
  }
  .bento-card.is-half {
    grid-column: span 3;
  }
}

.bento-card {
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 18px;
  padding: 24px;
  border-radius: 26px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-surface-elevated);
  box-shadow: var(--cook-glass-shadow);
  transition:
    transform 0.28s cubic-bezier(0.22, 1, 0.36, 1),
    border-color 0.28s ease;
}

.bento-card::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(320px circle at var(--mx, 50%) var(--my, 50%), rgba(224, 159, 31, 0.09), transparent 70%);
  opacity: 0;
  transition: opacity 0.3s ease;
  pointer-events: none;
}

.bento-card:hover {
  transform: translateY(-3px);
  border-color: rgba(224, 159, 31, 0.55);
}

.bento-card:hover::before {
  opacity: 1;
}

.bento-card.is-sky {
  background: linear-gradient(145deg, rgba(255, 241, 209, 0.65), var(--cook-surface-elevated) 62%);
}

.bento-card.is-leaf {
  background: linear-gradient(145deg, rgba(223, 245, 236, 0.68), var(--cook-surface-elevated) 62%);
}

.dark .bento-card.is-sky {
  background: linear-gradient(145deg, rgba(58, 49, 36, 0.56), var(--cook-surface-elevated) 65%);
}

.dark .bento-card.is-leaf {
  background: linear-gradient(145deg, rgba(24, 62, 50, 0.62), var(--cook-surface-elevated) 65%);
}

.dark .bento-card::before {
  background: radial-gradient(320px circle at var(--mx, 50%) var(--my, 50%), rgba(242, 205, 101, 0.1), transparent 70%);
}

.dark .bento-card:hover {
  border-color: rgba(143, 180, 255, 0.45);
}

.bento-card h3 {
  color: var(--cook-hero-text);
  font-family: var(--cook-font-display);
  font-size: 20px;
  font-weight: 700;
  line-height: 1.3;
}

.bento-card p {
  margin-top: 8px;
  color: var(--cook-hero-muted);
  font-size: 14px;
  line-height: 1.7;
  text-wrap: pretty;
}

.bento-visual {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.mini-ingredient {
  display: grid;
  width: 58px;
  height: 58px;
  place-items: center;
  border-radius: 17px;
  border: 1px solid var(--cook-glass-border);
  background: rgba(255, 255, 255, 0.78);
  transition: transform 0.35s cubic-bezier(0.22, 1, 0.36, 1) calc(var(--i, 0) * 35ms);
}

.dark .mini-ingredient {
  background: rgba(255, 255, 255, 0.08);
}

.bento-card:hover .mini-ingredient {
  transform: translateY(-4px) rotate(calc((var(--i, 0) - 2.5) * 2deg));
}

.mini-ingredient img {
  width: 42px;
  height: 42px;
  object-fit: contain;
}

.mini-timer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 13px;
  border-radius: 14px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
}

.mini-timer-step {
  color: var(--cook-hero-muted);
  font-size: 12px;
  font-weight: 550;
}

.mini-timer-clock {
  padding: 3px 9px;
  border-radius: 999px;
  background: rgba(249, 115, 22, 0.14);
  color: var(--cook-flame-2);
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.mini-fridge,
.bento-pill-stack,
.mini-adapt {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.fridge-pill,
.pill-chip,
.adapt-chip {
  padding: 6px 11px;
  border-radius: 999px;
  border: 1px solid var(--cook-glass-border);
  background: rgba(255, 255, 255, 0.72);
  color: var(--cook-hero-text);
  font-size: 12px;
  font-weight: 550;
}

.dark .fridge-pill,
.dark .pill-chip,
.dark .adapt-chip {
  background: rgba(255, 255, 255, 0.08);
}

.fridge-pill.is-warn {
  border-color: rgba(234, 88, 12, 0.28);
  background: rgba(249, 115, 22, 0.12);
  color: var(--cook-flame-2);
}

.fridge-pill.is-fresh,
.pill-chip.is-offline {
  border-color: rgba(10, 122, 90, 0.26);
  background: var(--cook-leaf-soft);
  color: var(--cook-leaf);
}

.mini-footprint {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 14px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
}

.footprint-dots {
  display: flex;
  gap: 6px;
}

.footprint-dot {
  width: 11px;
  height: 11px;
  border-radius: 4px;
  background: var(--cook-panel-border);
  opacity: 0.6;
}

.footprint-dot.is-on {
  background: var(--cook-leaf);
  opacity: 1;
}

.footprint-label {
  color: var(--cook-hero-muted);
  font-size: 12px;
  font-weight: 550;
}

@media (prefers-reduced-motion: reduce) {
  .bento-card,
  .bento-card::before,
  .mini-ingredient {
    transition: none;
  }
  .bento-card:hover,
  .bento-card:hover .mini-ingredient {
    transform: none;
  }
}
</style>
