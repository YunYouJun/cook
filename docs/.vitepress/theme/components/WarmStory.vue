<script setup lang="ts">
import { total } from '../landing'

const storyImage = '/landing/tomato-eggs-table.webp'
const stats = [
  { value: `${total} 道`, label: '家常菜谱' },
  { value: '免登录', label: '打开就能找菜' },
  { value: '可离线', label: '文字做法随身带' },
]
</script>

<template>
  <section class="warm-story" aria-labelledby="story-title">
    <div class="story-media" data-reveal>
      <figure class="story-frame">
        <img
          :src="storyImage"
          alt="阳光洒在餐桌上的番茄炒蛋与米饭，旁边放着新鲜番茄和鸡蛋"
          width="1024"
          height="1280"
          loading="lazy"
          decoding="async"
        >
        <figcaption class="story-badge">
          <span aria-hidden="true">🍅</span>
          <span>番茄炒蛋 · 家里常备两样食材就能端上桌</span>
        </figcaption>
      </figure>
    </div>

    <div class="story-body" data-reveal style="--reveal-delay: 90ms">
      <span class="section-eyebrow">写给每一个认真生活的你</span>
      <h2 id="story-title" class="section-title">
        <span class="title-clause">认真吃饭，</span><span class="title-clause">是对普通一天最好的收尾。</span>
      </h2>
      <p class="story-copy">
        忙碌了一整天，最消耗人的往往不是切菜洗锅，而是打开冰箱门却不知道做什么的那十分钟。云食册想轻巧地接住这个瞬间——点一点手头剩下的食材，或者直接说一句「半颗白菜两个蛋，不要辣」，几秒钟就能端出今晚的答案。
      </p>
      <p class="story-whisper">
        不用追求满汉全席，哪怕一盘热腾腾的番茄炒蛋，也足够犒劳今天的自己。
      </p>
      <dl class="story-stats">
        <div v-for="stat in stats" :key="stat.label" class="stat-card">
          <dt>{{ stat.value }}</dt>
          <dd>{{ stat.label }}</dd>
        </div>
      </dl>
    </div>
  </section>
</template>

<style scoped>
.warm-story {
  display: grid;
  gap: 36px;
  align-items: center;
}

@media (min-width: 900px) {
  .warm-story {
    grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
    gap: 56px;
  }
}

.story-frame {
  position: relative;
  overflow: hidden;
  border-radius: 30px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-surface-elevated);
  box-shadow:
    0 28px 60px -30px rgba(48, 36, 20, 0.28),
    var(--cook-glass-shadow);
  aspect-ratio: 4 / 4.4;
}

.story-frame img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.7s cubic-bezier(0.22, 1, 0.36, 1);
}

.story-frame:hover img {
  transform: scale(1.03);
}

.story-badge {
  position: absolute;
  left: 16px;
  right: 16px;
  bottom: 16px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: fit-content;
  max-width: calc(100% - 32px);
  padding: 10px 14px;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  background: rgba(255, 252, 245, 0.88);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: 0 10px 24px -12px rgba(48, 36, 20, 0.25);
  color: #2a241c;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
}

.dark .story-badge {
  border-color: rgba(255, 255, 255, 0.16);
  background: rgba(20, 29, 46, 0.86);
  color: #f3f6fc;
}

.title-clause {
  display: inline-block;
}

.story-copy {
  margin-top: 18px;
  color: var(--cook-hero-muted);
  font-size: 16px;
  line-height: 1.85;
  text-wrap: pretty;
}

.story-whisper {
  margin-top: 14px;
  padding-left: 14px;
  border-left: 3px solid var(--cook-flame-1);
  color: var(--cook-hero-text);
  font-size: 16px;
  font-weight: 500;
  line-height: 1.75;
  text-wrap: pretty;
}

.story-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-top: 28px;
}

.stat-card {
  padding: 16px 14px;
  border-radius: 18px;
  border: 1px solid var(--cook-glass-border);
  background: var(--cook-glass-bg);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: var(--cook-glass-shadow);
  transition:
    transform 0.25s cubic-bezier(0.22, 1, 0.36, 1),
    border-color 0.25s ease;
}

.stat-card:hover {
  transform: translateY(-2px);
  border-color: var(--cook-leaf);
}

.stat-card dt {
  color: var(--cook-hero-text);
  font-family: var(--cook-font-display);
  font-size: clamp(20px, 2.3vw, 24px);
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1.25;
}

.stat-card dd {
  margin-top: 5px;
  color: var(--cook-hero-muted);
  font-size: 12px;
  line-height: 1.4;
}

@media (max-width: 639px) {
  .story-frame {
    border-radius: 24px;
    aspect-ratio: 4 / 3.8;
  }
  .story-stats {
    gap: 8px;
  }
  .stat-card {
    padding: 12px 10px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .story-frame img,
  .stat-card {
    transition: none;
  }
  .story-frame:hover img,
  .stat-card:hover {
    transform: none;
  }
}
</style>
