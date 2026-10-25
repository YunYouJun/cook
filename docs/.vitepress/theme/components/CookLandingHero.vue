<script setup lang="ts">
import { computed, ref } from 'vue'
import { links, testflightUrl } from '../constants'
import { emojiOf, flyEmoji, prefersReducedMotion, recommend, stuffGroups, total } from '../landing'

const ingredients = stuffGroups.flatMap(group => group.items)

const selected = ref<string[]>(['番茄', '鸡蛋'])
const matches = computed(() => recommend({ stuff: selected.value }))
const steam = computed(() => matches.value.slice(0, 4))
const steamKey = computed(() => selected.value.join('、') || 'empty')

const sky = ref<HTMLElement>()
const pot = ref<SVGSVGElement>()
const potBump = ref(0)

/** 选中时 emoji 沿弧线落进锅里，和 App 里食材飞进菜篮子是同一条曲线 */
function toggle(name: string, event: MouseEvent) {
  const adding = !selected.value.includes(name)
  selected.value = adding ? [...selected.value, name] : selected.value.filter(item => item !== name)
  const emoji = emojiOf(name)
  if (!adding || !emoji || !sky.value || !pot.value || prefersReducedMotion())
    return
  const potRect = pot.value.getBoundingClientRect()
  // 落点在锅口，不是锅的正中
  const mouth = new DOMRect(potRect.left + potRect.width * 0.45, potRect.top + potRect.height * 0.5, 0, 0)
  void flyEmoji(sky.value, emoji, (event.currentTarget as HTMLElement).getBoundingClientRect(), mouth, { size: 30, duration: 560, lift: 140 })
    .then(() => { potBump.value++ })
}

function missingText(missing: string[]) {
  return missing.length ? `还差${missing.join('、')}` : '食材齐了'
}
</script>

<template>
  <!-- 首屏：放大的 App 图标，锅里冒出的蒸汽就是能做的菜 -->
  <section ref="sky" class="sky" aria-labelledby="landing-title">
    <div class="sky-copy">
      <div class="app-id">
        <img class="app-icon" src="/brand/cook-app-icon.svg" alt="" width="64" height="64">
        <div>
          <p class="app-name">
            云食册
          </p>
          <p class="app-status">
            <span class="status-dot" :class="{ 'is-live': testflightUrl }" aria-hidden="true" />
            {{ testflightUrl ? 'iOS 版体验测试进行中' : 'iOS 体验报名已开放' }}
          </p>
        </div>
      </div>
      <h1 id="landing-title" class="sky-title">
        有什么食材，<br>就做什么菜
      </h1>
      <p class="sky-lead">
        食用手册的原生 iOS 版。选几样冰箱里有的，从 {{ total }} 道菜谱里找做菜思路，看看哪些食材齐了、哪些还差一点，再查看图文或视频做法。
      </p>
      <div class="sky-actions">
        <a v-if="testflightUrl" class="btn btn-yolk" :href="testflightUrl" target="_blank" rel="noopener">加入 TestFlight 测试</a>
        <a v-else class="btn btn-yolk" :href="links.signup">报名 iOS 体验</a>
        <a class="btn btn-ghost" :href="links.web" target="_blank" rel="noopener">先用网页版</a>
      </div>
      <p class="sky-note">
        需要 iOS 17 或更新版本。<a href="#beta">了解测试与安装安排</a>
      </p>
    </div>

    <div class="pot-demo">
      <div class="steam" aria-hidden="true">
        <Transition name="steam" mode="out-in">
          <ol :key="steamKey" class="steam-list">
            <li v-if="!selected.length" class="cloud cloud-hint" style="--i: 0">
              先选几样食材
            </li>
            <li v-else-if="!steam.length" class="cloud cloud-hint" style="--i: 0">
              这几样凑不出一道菜，换一样试试
            </li>
            <li v-for="(match, index) in steam" :key="match.sourceIndex" class="cloud" :style="{ '--i': index }">
              <span class="cloud-name">{{ match.recipe.name }}</span>
              <span class="cloud-meta">{{ missingText(match.missingStuff) }}</span>
            </li>
          </ol>
        </Transition>
      </div>

      <svg ref="pot" :key="potBump" class="pot" :class="{ 'is-bump': potBump > 0 }" viewBox="0 -40 100 96" role="img" :aria-label="`能做 ${matches.length} 道`">
        <path d="M66 10 86.5 -30.5" class="pot-spoon" stroke-width="6" stroke-linecap="round" />
        <circle cx="87.5" cy="-32" r="5" class="pot-spoon-head" />
        <rect x="0" y="4" width="100" height="8" rx="4" class="pot-lid" />
        <path d="M8 15h84v17a16 16 0 0 1-16 16H24A16 16 0 0 1 8 32Z" class="pot-body" />
        <text x="50" y="34.5" text-anchor="middle" class="pot-count">
          能做 {{ matches.length }} 道
        </text>
      </svg>
      <p class="sr-only" aria-live="polite">
        已选 {{ selected.length }} 样食材，能做 {{ matches.length }} 道菜{{ steam.length ? `，比如${steam.map(m => m.recipe.name).join('、')}` : '' }}
      </p>

      <div class="landing-chips" role="group" aria-label="选食材，试试能做什么">
        <button
          v-for="item in ingredients"
          :key="item.name"
          type="button"
          class="landing-chip"
          :aria-pressed="selected.includes(item.name)"
          @click="toggle(item.name, $event)"
        >
          <span aria-hidden="true">{{ item.emoji }}</span>{{ item.name }}
        </button>
        <button v-if="selected.length" type="button" class="landing-chip landing-chip-clear" @click="selected = []">
          清空
        </button>
      </div>
      <p class="pot-caption">
        在这里先试试：用的是 App 里同一套菜谱和推荐规则，默认家里有油和盐。
      </p>
    </div>
  </section>
</template>
