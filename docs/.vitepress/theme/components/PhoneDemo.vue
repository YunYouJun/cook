<script setup lang="ts">
import type { RecipeItem } from '../../../../packages/cook/src/types/recipe'
import type { RecipeMatch } from '../../../../packages/cook/src/utils/recommend'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { parseCookQuery } from '../../../../packages/cook/src/utils/recommend'
import { catalog, describe, emojiOf, flyEmoji, groupLabels, meta, prefersReducedMotion, recommend, stuffGroups, thumbnail, tweenNumber } from '../landing'

/**
 * 首页的 iPhone 演示：照着 App 的界面和动效，用真实菜谱数据自动播放四段。
 * 推荐、整句识别都调用和 App 同一套逻辑，演示里的数字和菜名都是真的。
 */

type Screen = 'cook' | 'results' | 'fridge' | 'random'
type ChapterId = 'pick' | 'sentence' | 'fridge' | 'random'
interface Ctx {
  fast: boolean
  wait: (ms: number) => Promise<void>
}

const SENTENCE = '冰箱里有半颗白菜两个蛋，不要辣'
const PICKS = ['土豆', '番茄', '鸡蛋']
/** 放入几天前，和 shelf-life.json 的冷藏天数对得上 */
const SOON = [
  { name: '豆腐', label: '今天到期', added: 3 },
  { name: '菠菜', label: '明天到期', added: 2 },
]
const FRESH = [
  { name: '胡萝卜', label: '还剩 12 天', added: 9 },
  { name: '土豆', label: '还剩 21 天', added: 9 },
  { name: '洋葱', label: '还剩 25 天', added: 5 },
  { name: '鸡蛋', label: '还剩 26 天', added: 4 },
]

const chapters: { id: ChapterId, title: string, short: string, text: string, duration: number }[] = [
  { id: 'pick', title: '点几样食材', short: '点食材', text: '点一下冰箱里有的，食材飞进篮子，能做几道马上跳出来。', duration: 8200 },
  { id: 'sentence', title: '说一句话', short: '说句话', text: `打一句「${SENTENCE}」，自动拆成食材和口味。`, duration: 7600 },
  { id: 'fridge', title: '快过期了提醒你', short: '临期提醒', text: '记下买回来的食材，主动开启提醒后，由设备在本地通知你。', duration: 8400 },
  { id: 'random', title: '今天吃什么', short: '随机', text: '拿不定主意就摇一摇手机，换一道。', duration: 7400 },
]

const tabs = [
  { id: 'cook', label: '做菜', icon: 'M8 1.5a3.35 3.35 0 0 0 0 6.7h1.53c.39 0 .76.1 1.08.3h2.02c-.58-1.05-1.77-1.75-3.1-1.75H8c-1 0-1.85-.98-1.85-2S7 3 8 3zm4.85.5c0 1-.85 1.85-1.85 1.85v1.5c1.92 0 3.5 1.35 3.89 3.15h1.53a5.54 5.54 0 0 0-3.07-4.12c.62-.61 1-1.45 1-2.38zM3 10v2h2v7a2 2 0 0 0 2 2h10c1.11 0 2-.89 2-2v-7h2v-2zm4 2h10v7H7z' },
  { id: 'fridge', label: '冰箱', icon: 'M9 21v1H7v-1a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2v1h-2v-1zM7 4v5h10V4zm0 15h10v-8H7zm1-7h2v3H8zm0-6h2v2H8z' },
  { id: 'library', label: '菜谱', icon: 'M12 21.5c-1.35-.85-3.8-1.5-5.5-1.5c-1.65 0-3.35.3-4.75 1.05c-.1.05-.15.05-.25.05c-.25 0-.5-.25-.5-.5V6c.6-.45 1.25-.75 2-1c1.11-.35 2.33-.5 3.5-.5c1.95 0 4.05.4 5.5 1.5c1.45-1.1 3.55-1.5 5.5-1.5c1.17 0 2.39.15 3.5.5c.75.25 1.4.55 2 1v14.6c0 .25-.25.5-.5.5c-.1 0-.15 0-.25-.05c-1.4-.75-3.1-1.05-4.75-1.05c-1.7 0-4.15.65-5.5 1.5m-1-14c-1.36-.6-3.16-1-4.5-1c-1.2 0-2.4.15-3.5.5v11.5c1.1-.35 2.3-.5 3.5-.5c1.34 0 3.14.4 4.5 1zM13 19c1.36-.6 3.16-1 4.5-1c1.2 0 2.4.15 3.5.5V7c-1.1-.35-2.3-.5-3.5-.5c-1.34 0-3.14.4-4.5 1z' },
  { id: 'random', label: '随机', icon: 'M19 5v14H5V5zm0-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2M7.5 6C6.7 6 6 6.7 6 7.5S6.7 9 7.5 9S9 8.3 9 7.5S8.3 6 7.5 6m9 9c-.8 0-1.5.7-1.5 1.5s.7 1.5 1.5 1.5s1.5-.7 1.5-1.5s-.7-1.5-1.5-1.5m0-9c-.8 0-1.5.7-1.5 1.5S15.7 9 16.5 9S18 8.3 18 7.5S17.3 6 16.5 6M12 10.5c-.8 0-1.5.7-1.5 1.5s.7 1.5 1.5 1.5s1.5-.7 1.5-1.5s-.7-1.5-1.5-1.5M7.5 15c-.8 0-1.5.7-1.5 1.5S6.7 18 7.5 18S9 17.3 9 16.5S8.3 15 7.5 15' },
  { id: 'me', label: '我的', icon: 'M12 2A10 10 0 0 0 2 12a10 10 0 0 0 10 10a10 10 0 0 0 10-10A10 10 0 0 0 12 2M7.07 18.28c.43-.9 3.05-1.78 4.93-1.78s4.5.88 4.93 1.78A7.9 7.9 0 0 1 12 20c-1.86 0-3.57-.64-4.93-1.72m11.29-1.45c-1.43-1.74-4.9-2.33-6.36-2.33s-4.93.59-6.36 2.33A7.93 7.93 0 0 1 4 12c0-4.41 3.59-8 8-8s8 3.59 8 8c0 1.82-.62 3.5-1.64 4.83M12 6c-1.94 0-3.5 1.56-3.5 3.5S10.06 13 12 13s3.5-1.56 3.5-3.5S13.94 6 12 6m0 5a1.5 1.5 0 0 1-1.5-1.5A1.5 1.5 0 0 1 12 8a1.5 1.5 0 0 1 1.5 1.5A1.5 1.5 0 0 1 12 11' },
]

const icons = {
  filter: 'M6 13h12v-2H6M3 6v2h18V6M10 18h4v-2h-4z',
  search: 'M9.5 3A6.5 6.5 0 0 1 16 9.5c0 1.61-.59 3.09-1.56 4.23l.27.27h.79l5 5l-1.5 1.5l-5-5v-.79l-.27-.27A6.52 6.52 0 0 1 9.5 16A6.5 6.5 0 0 1 3 9.5A6.5 6.5 0 0 1 9.5 3m0 2C7 5 5 7 5 9.5S7 14 9.5 14S14 12 14 9.5S12 5 9.5 5',
  back: 'M15.41 16.58L10.83 12l4.58-4.59L14 6l-6 6l6 6z',
  next: 'M8.59 16.58L13.17 12L8.59 7.41L10 6l6 6l-6 6z',
  plus: 'M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6z',
  check: 'M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10s10-4.5 10-10S17.5 2 12 2m-2 15l-5-5l1.41-1.41L10 14.17l7.59-7.59L19 8z',
  dice: 'M14 8c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1m-2 4v7H5v-7zm.78-2H4.22C3.55 10 3 10.55 3 11.22v8.56c0 .67.55 1.22 1.22 1.22h8.56c.67 0 1.22-.55 1.22-1.22v-8.56c0-.67-.55-1.22-1.22-1.22m7-7h-8.56C10.55 3 10 3.55 10 4.22V8h2V5h7v7h-3v2h3.78c.67 0 1.22-.55 1.22-1.22V4.22C21 3.55 20.45 3 19.78 3M17 8c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1m0 3c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1M7 15c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1m3 3c-.55 0-1-.45-1-1s.45-1 1-1s1 .45 1 1s-.45 1-1 1',
}

/** 随机页从这些菜里抽：视频口碑好、做法简单 */
const preferredPool: RecipeItem[] = catalog.filter(recipe => (recipe.score ?? 0) >= 70 && recipe.difficulty === '简单' && thumbnail(recipe) !== '🍽️')

// ---------- 界面状态 ----------

const pool = preferredPool.length ? preferredPool : catalog

const root = ref<HTMLElement>()
const screenEl = ref<HTMLElement>()
const screen = ref<Screen>('cook')
const transitionName = ref('fade')
const resultsFrom = ref<'cook' | 'fridge'>('cook')
const tab = computed(() => screen.value === 'results' ? resultsFrom.value : screen.value)

const selected = ref<string[]>([])
const basket = ref<string[]>([])
const noSpicy = ref(false)
const query = ref('')
const searching = ref(false)
const notice = ref('')
const bounceKey = ref(0)
const displayCount = ref(0)

const resultStuff = ref<string[]>([])
const banner = ref(false)
const card = shallowRef<RecipeItem>(pool[0]!)
const shuffleEmoji = ref('')
const tossKey = ref(0)
const shaking = ref(false)
const touch = ref({ x: 0, y: 0, n: 0 })
const today = ref<Date>()

const active = ref(0)
const running = ref(false)
const userPaused = shallowRef(true)
const reduced = ref(false)
const progressKey = ref(0)

const cookCount = computed(() => selected.value.length ? recommend({ stuff: selected.value, noSpicy: noSpicy.value }).length : 0)

const resultMatches = computed(() => resultStuff.value.length ? recommend({ stuff: resultStuff.value, noSpicy: noSpicy.value }) : [])
/** 结果页：按缺几样分组，最多露出 7 行 */
const resultGroups = computed(() => {
  const groups: { label: string, items: { match: RecipeMatch, index: number }[] }[] = []
  let index = 0
  for (let missing = 0; missing < groupLabels.length && index < 7; missing++) {
    const all = resultMatches.value.filter(match => match.missingStuff.length === missing)
    if (!all.length)
      continue
    const items = all.slice(0, 7 - index).map(match => ({ match, index: index++ }))
    groups.push({ label: `${groupLabels[missing]} · ${all.length}`, items })
  }
  return groups
})

let stopTween = () => {}
watch(cookCount, (to) => {
  stopTween()
  if (reduced.value || !running.value)
    displayCount.value = to
  else
    stopTween = tweenNumber(displayCount.value, to, value => displayCount.value = value)
})

function addedText(daysAgo: number) {
  if (!today.value)
    return ''
  const date = new Date(today.value.getTime() - daysAgo * 86_400_000)
  return `${date.getMonth() + 1}月${date.getDate()}日放入`
}

// ---------- 播放 ----------

let runId = 0
let playbackController = new AbortController()

function createCtx(fast: boolean): Ctx {
  const signal = playbackController.signal
  return {
    fast,
    async wait(ms) {
      signal.throwIfAborted()
      if (!fast) {
        await new Promise<void>((resolve, reject) => {
          let timer: ReturnType<typeof setTimeout>
          const abort = () => {
            clearTimeout(timer)
            reject(signal.reason)
          }
          timer = setTimeout(() => {
            signal.removeEventListener('abort', abort)
            resolve()
          }, ms)
          signal.addEventListener('abort', abort, { once: true })
        })
      }
      signal.throwIfAborted()
    },
  }
}

// Settle pending waits and stop flights before starting another chapter.
function cancel() {
  runId++
  playbackController.abort()
  playbackController = new AbortController()
  stopTween()
}

function go(next: Screen) {
  const prev = screen.value
  transitionName.value = next === 'results' ? 'push' : prev === 'results' && next === resultsFrom.value ? 'pop' : 'fade'
  screen.value = next
}

function reset(id: ChapterId) {
  selected.value = []
  basket.value = []
  noSpicy.value = false
  query.value = ''
  searching.value = false
  notice.value = ''
  banner.value = false
  shaking.value = false
  shuffleEmoji.value = ''
  go(id === 'fridge' ? 'fridge' : id === 'random' ? 'random' : 'cook')
}

function find(key: string) {
  return screenEl.value?.querySelector<HTMLElement>(`[data-demo="${key}"]`) ?? undefined
}

/** 手指点一下：在元素上出现一个触点，元素按下去再弹回来 */
async function tap(ctx: Ctx, key: string) {
  if (ctx.fast)
    return
  const el = find(key)
  const base = screenEl.value?.getBoundingClientRect()
  if (!el || !base)
    return
  const rect = el.getBoundingClientRect()
  touch.value = { x: rect.left + rect.width / 2 - base.left, y: rect.top + rect.height / 2 - base.top, n: touch.value.n + 1 }
  el.classList.add('is-pressed')
  try {
    await ctx.wait(240)
  }
  finally {
    el.classList.remove('is-pressed')
  }
}

function land(name: string) {
  basket.value = [...basket.value.filter(item => item !== name), name].slice(-3)
  bounceKey.value++
}

/** 选中食材，emoji 沿弧线飞进底部按钮，和 App 的 BasketFlight 一样 */
function pick(ctx: Ctx, name: string) {
  selected.value = [...selected.value, name]
  const chip = find(`chip-${name}`)
  const target = find('see')
  const emoji = emojiOf(name)
  if (ctx.fast || !chip || !target || !screenEl.value || !emoji) {
    land(name)
    return
  }
  const id = runId
  void flyEmoji(screenEl.value, emoji, chip.getBoundingClientRect(), target.getBoundingClientRect(), { signal: playbackController.signal }).then(() => {
    if (id === runId)
      land(name)
  })
}

function openResults(stuff: string[], from: 'cook' | 'fridge') {
  resultStuff.value = stuff
  resultsFrom.value = from
  go('results')
}

let rollIndex = 0
async function roll(ctx: Ctx) {
  rollIndex = (rollIndex + 1 + Math.floor(Math.random() * 5)) % pool.length
  if (!ctx.fast) {
    shuffleEmoji.value = thumbnail(card.value)
    for (const delay of [55, 60, 70, 80, 95, 115, 140, 170]) {
      shuffleEmoji.value = thumbnail(pool[Math.floor(Math.random() * pool.length)]!)
      await ctx.wait(delay)
    }
  }
  card.value = pool[rollIndex]!
  shuffleEmoji.value = ''
  tossKey.value++
}

const scripts: Record<ChapterId, (ctx: Ctx) => Promise<void>> = {
  async pick(ctx) {
    await ctx.wait(700)
    for (const name of PICKS) {
      await tap(ctx, `chip-${name}`)
      pick(ctx, name)
      await ctx.wait(620)
    }
    await ctx.wait(400)
    await tap(ctx, 'see')
    openResults(selected.value, 'cook')
  },
  async sentence(ctx) {
    await ctx.wait(600)
    await tap(ctx, 'search')
    searching.value = true
    if (ctx.fast) {
      query.value = SENTENCE
    }
    else {
      for (const char of SENTENCE) {
        query.value += char
        await ctx.wait(char === '，' ? 260 : 85)
      }
    }
    await ctx.wait(500)
    const parsed = parseCookQuery(SENTENCE)
    searching.value = false
    query.value = ''
    notice.value = `已按「${describe(parsed)}」筛选`
    noSpicy.value = parsed.noSpicy
    await ctx.wait(250)
    for (const name of parsed.stuff) {
      pick(ctx, name)
      await ctx.wait(180)
    }
  },
  async fridge(ctx) {
    await ctx.wait(900)
    banner.value = true
    await ctx.wait(2800)
    if (ctx.fast)
      return
    banner.value = false
    await ctx.wait(450)
    await tap(ctx, 'use-soon')
    openResults(SOON.map(item => item.name), 'fridge')
  },
  async random(ctx) {
    await ctx.wait(700)
    await tap(ctx, 'roll')
    await roll(ctx)
    await ctx.wait(1700)
    if (ctx.fast)
      return
    shaking.value = true
    await ctx.wait(560)
    shaking.value = false
    await roll(ctx)
  },
}

async function play(index: number) {
  cancel()
  const id = runId
  const chapter = chapters[index]!
  active.value = index
  reset(chapter.id)
  running.value = !reduced.value
  progressKey.value++
  const ctx = createCtx(reduced.value)
  try {
    await Promise.all([scripts[chapter.id](ctx), ctx.wait(chapter.duration)])
    if (id === runId) {
      running.value = false
      userPaused.value = true
    }
  }
  catch (error) {
    if (id !== runId)
      return
    running.value = false
    userPaused.value = true
    throw error
  }
}

function stop() {
  cancel()
  running.value = false
  displayCount.value = cookCount.value
}

let visible = false
let observer: IntersectionObserver | undefined

/** 看不见（滚走了、切到别的标签页）就停，回来时从这一段开头接着播 */
function sync() {
  if (reduced.value)
    return
  const shouldPlay = visible && !document.hidden && !userPaused.value
  if (shouldPlay && !running.value)
    void play(active.value)
  else if (!shouldPlay && running.value)
    stop()
}

function choose(index: number) {
  userPaused.value = false
  void play(index)
}

function togglePause() {
  if (running.value) {
    userPaused.value = true
    stop()
  }
  else {
    choose(active.value)
  }
}

let motionQuery: MediaQueryList | undefined
function updateMotion() {
  reduced.value = motionQuery?.matches ?? false
  userPaused.value = true
  stop()
}

onMounted(() => {
  today.value = new Date()
  reduced.value = prefersReducedMotion()
  motionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  motionQuery.addEventListener('change', updateMotion)
  if (typeof IntersectionObserver !== 'undefined') {
    observer = new IntersectionObserver(([entry]) => {
      visible = !!entry?.isIntersecting
      sync()
    }, { threshold: 0.35 })
  }
  if (root.value)
    observer?.observe(root.value)
  document.addEventListener('visibilitychange', sync)
})

onBeforeUnmount(() => {
  stop()
  stopTween()
  observer?.disconnect()
  motionQuery?.removeEventListener('change', updateMotion)
  document.removeEventListener('visibilitychange', sync)
})
</script>

<template>
  <div ref="root" class="demo" :class="{ 'is-paused': !running }">
    <div class="demo-side">
      <ol class="chapters" aria-label="演示内容">
        <li v-for="(chapter, index) in chapters" :key="chapter.id">
          <button
            type="button"
            class="chapter"
            :class="{ 'is-active': index === active }"
            :aria-current="index === active ? 'step' : undefined"
            @click="choose(index)"
          >
            <span class="chapter-bar" aria-hidden="true">
              <span
                v-if="index === active && running"
                :key="progressKey"
                class="chapter-fill"
                :style="{ animationDuration: `${chapter.duration}ms` }"
              />
              <span v-else-if="index === active || index < active" class="chapter-fill is-full" />
            </span>
            <span class="chapter-title"><span class="is-long">{{ chapter.title }}</span><span class="is-short">{{ chapter.short }}</span></span>
            <span class="chapter-text">{{ chapter.text }}</span>
          </button>
        </li>
      </ol>
      <p class="chapter-now" aria-hidden="true">
        {{ chapters[active]!.text }}
      </p>
      <div class="demo-foot">
        <button type="button" class="pause" @click="togglePause">
          {{ reduced ? '查看本节结果' : running ? '暂停演示' : '播放 / 重播本节' }}
        </button>
        <span>网页模拟界面，使用真实菜谱数据；不是 App 录屏。每次播放一节，暂停后重播。</span>
        <noscript>演示需要 JavaScript；下方可查看真实截图。</noscript>
      </div>
    </div>

    <div class="phone" :class="{ 'is-shaking': shaking }" aria-hidden="true">
      <div ref="screenEl" class="screen">
        <div class="island" />
        <div class="status">
          <span>9:41</span>
          <span class="status-icons">
            <svg viewBox="0 0 18 12"><rect x="0" y="8" width="3" height="4" rx="1" /><rect x="5" y="5.5" width="3" height="6.5" rx="1" /><rect x="10" y="3" width="3" height="9" rx="1" /><rect x="15" y="0" width="3" height="12" rx="1" /></svg>
            <svg viewBox="0 0 26 12"><rect x="0.5" y="0.5" width="22" height="11" rx="3.5" fill="none" stroke="currentColor" opacity="0.4" /><rect x="2" y="2" width="17" height="8" rx="2" /><rect x="24" y="4" width="1.6" height="4" rx="0.8" opacity="0.4" /></svg>
          </span>
        </div>

        <Transition :name="transitionName">
          <section v-if="screen === 'cook'" key="cook" class="scr">
            <div class="toolbar">
              <span class="round"><svg viewBox="0 0 24 24"><path :d="icons.filter" /></svg></span>
              <span class="capsule" :class="{ 'is-dim': !selected.length }">清空</span>
            </div>
            <h3 class="large-title">
              做菜
            </h3>
            <div class="search" data-demo="search" :class="{ 'is-focused': searching }">
              <svg viewBox="0 0 24 24"><path :d="icons.search" /></svg>
              <span v-if="searching" class="search-text">{{ query }}<i class="caret" /></span>
              <span v-else class="search-placeholder">说说有什么，如：土豆和鸡蛋，不要辣</span>
            </div>
            <Transition name="notice">
              <div v-if="notice" class="card notice">
                <svg viewBox="0 0 24 24"><path :d="icons.check" /></svg>
                <span>{{ notice }}</span>
              </div>
            </Transition>
            <template v-for="group in stuffGroups.slice(0, 2)" :key="group.title">
              <p class="group-title">
                {{ group.title }}
              </p>
              <div class="card chip-grid">
                <span
                  v-for="item in group.items"
                  :key="item.name"
                  class="chip"
                  :class="{ 'is-on': selected.includes(item.name) }"
                  :data-demo="`chip-${item.name}`"
                >
                  <i>{{ item.emoji }}</i>{{ item.name }}
                </span>
              </div>
            </template>
            <div class="see-wrap">
              <span
                :key="bounceKey"
                class="see"
                :class="{ 'is-off': !selected.length, 'is-bounce': bounceKey > 0 }"
                data-demo="see"
              >
                <span v-if="basket.length" class="basket">
                  <i v-for="name in basket" :key="name">{{ emojiOf(name) }}</i>
                </span>
                <template v-if="selected.length">看看能做什么（<b class="count">{{ displayCount }}</b>）</template>
                <template v-else>先选几样食材</template>
              </span>
            </div>
          </section>

          <section v-else-if="screen === 'results'" key="results" class="scr">
            <div class="toolbar">
              <span class="round"><svg viewBox="0 0 24 24"><path :d="icons.back" /></svg></span>
            </div>
            <h3 class="large-title">
              能做的菜
            </h3>
            <div class="card cell">
              <span>推荐方式</span>
              <span class="secondary">全部推荐</span>
            </div>
            <p class="footnote">
              已选：{{ resultStuff.join('、') }}<template v-if="noSpicy">
                ；不吃辣
              </template>
            </p>
            <template v-for="group in resultGroups" :key="group.label">
              <p class="group-title">
                {{ group.label }}
              </p>
              <div class="card">
                <div v-for="{ match, index } in group.items" :key="match.recipe.id ?? `${match.recipe.name}-${index}`" class="row" :style="{ '--i': index }">
                  <span class="row-emoji">{{ thumbnail(match.recipe) }}</span>
                  <span class="row-body">
                    <b>{{ match.recipe.name }}</b>
                    <small>{{ meta(match.recipe) }}</small>
                    <small v-if="match.missingStuff.length" class="missing">还缺 {{ match.missingStuff.join('、') }}</small>
                  </span>
                  <svg class="row-next" viewBox="0 0 24 24"><path :d="icons.next" /></svg>
                </div>
              </div>
            </template>
          </section>

          <section v-else-if="screen === 'fridge'" key="fridge" class="scr">
            <div class="toolbar is-end">
              <span class="round"><svg viewBox="0 0 24 24"><path :d="icons.plus" /></svg></span>
            </div>
            <h3 class="large-title">
              冰箱
            </h3>
            <div class="group-head">
              <p class="group-title">
                快过期
              </p>
              <span class="link" data-demo="use-soon">只用这几样做菜</span>
            </div>
            <div class="card">
              <div v-for="item in SOON" :key="item.name" class="row">
                <span class="row-emoji is-small">{{ emojiOf(item.name) }}</span>
                <span class="row-body">
                  <b>{{ item.name }}</b>
                  <small>{{ addedText(item.added) }}</small>
                </span>
                <span class="expiry is-soon">{{ item.label }}</span>
              </div>
            </div>
            <p class="footnote">
              今天到后天到期的，先用掉。
            </p>
            <p class="group-title">
              还新鲜
            </p>
            <div class="card">
              <div v-for="item in FRESH" :key="item.name" class="row">
                <span class="row-emoji is-small">{{ emojiOf(item.name) }}</span>
                <span class="row-body">
                  <b>{{ item.name }}</b>
                  <small>{{ addedText(item.added) }}</small>
                </span>
                <span class="expiry">{{ item.label }}</span>
              </div>
            </div>
          </section>

          <section v-else key="random" class="scr scr-random">
            <h3 class="large-title">
              今天吃什么
            </h3>
            <div class="rand-card">
              <span :key="tossKey" class="rand-emoji" :class="{ 'is-toss': tossKey > 0 }">{{ shuffleEmoji || thumbnail(card) }}</span>
              <b class="rand-name" :class="{ 'is-redacted': shuffleEmoji }">{{ card.name }}</b>
              <small :class="{ 'is-redacted': shuffleEmoji }">{{ meta(card) }}</small>
              <span class="link" :style="{ opacity: shuffleEmoji ? 0 : 1 }">看做法 ›</span>
            </div>
            <span class="roll" data-demo="roll">
              <svg viewBox="0 0 24 24"><path :d="icons.dice" /></svg>换一道
            </span>
          </section>
        </Transition>

        <Transition name="banner">
          <div v-if="banner" class="banner">
            <img src="/brand/cook-app-icon.svg" alt="" width="20" height="20">
            <div>
              <p class="banner-head">
                <b>冰箱里有食材快到期了</b><span>现在</span>
              </p>
              <p>豆腐今天到期，菠菜明天到期，看看能做什么吧。</p>
            </div>
          </div>
        </Transition>

        <nav class="tabbar">
          <span v-for="item in tabs" :key="item.id" class="tab" :class="{ 'is-on': tab === item.id }">
            <svg viewBox="0 0 24 24"><path :d="item.icon" /></svg>
            <small>{{ item.label }}</small>
          </span>
        </nav>

        <span v-if="touch.n" :key="touch.n" class="touch" :style="{ left: `${touch.x}px`, top: `${touch.y}px` }" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.demo {
  display: grid;
  gap: 32px;
  justify-items: center;
}

@media (min-width: 960px) {
  .demo {
    grid-template-columns: minmax(0, 400px) auto;
    justify-content: center;
    align-items: center;
    justify-items: stretch;
    gap: 120px;
  }
}

/* ---------- 章节 ---------- */

.demo-side {
  width: 100%;
  max-width: 420px;
}

.chapters {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

@media (min-width: 960px) {
  .chapters {
    grid-template-columns: 1fr;
    gap: 4px;
  }
}

.chapter {
  display: grid;
  gap: 8px;
  width: 100%;
  padding: 0;
  text-align: left;
  color: var(--vp-c-text-2);
  cursor: pointer;
}

@media (min-width: 960px) {
  .chapter {
    padding: 16px 0 18px;
  }
}

.chapter-bar {
  position: relative;
  height: 3px;
  border-radius: 2px;
  background: var(--vp-c-divider);
  overflow: hidden;
}

.chapter-fill {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  background: var(--vp-c-brand-1);
  transform-origin: left;
  animation: fill linear both;
}

.chapter-fill.is-full {
  animation: none;
}

@keyframes fill {
  from {
    transform: scaleX(0);
  }
}

.chapter-title {
  font-family: var(--cook-font-display);
  font-size: 15px;
  line-height: 1.3;
  transition: color 0.2s ease;
}

.chapter.is-active .chapter-title,
.chapter:hover .chapter-title {
  color: var(--vp-c-text-1);
}

.chapter-title .is-long {
  display: none;
}

@media (min-width: 960px) {
  .chapter-title .is-long {
    display: inline;
  }

  .chapter-title .is-short {
    display: none;
  }
}

.chapter-text {
  display: none;
  font-size: 15px;
  line-height: 1.7;
}

@media (min-width: 960px) {
  .chapter-title {
    font-size: 24px;
  }

  .chapter-text {
    display: block;
    max-height: 0;
    overflow: hidden;
    opacity: 0;
    transition:
      max-height 0.35s ease,
      opacity 0.35s ease;
  }

  .chapter.is-active .chapter-text {
    max-height: 6em;
    opacity: 1;
  }
}

.chapter:focus-visible {
  outline: 3px solid var(--vp-c-brand-1);
  outline-offset: 4px;
  border-radius: 4px;
}

.chapter-now {
  min-height: 3.4em;
  margin: 16px 0 0;
  font-size: 15px;
  line-height: 1.7;
  color: var(--vp-c-text-2);
}

@media (min-width: 960px) {
  .chapter-now {
    display: none;
  }
}

.demo-foot {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  margin-top: 8px;
  font-size: 13px;
  color: var(--vp-c-text-3);
}

@media (min-width: 960px) {
  .demo-foot {
    margin-top: 24px;
  }
}

.pause {
  min-height: 44px;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 13px;
  color: var(--vp-c-text-2);
  box-shadow: inset 0 0 0 1px var(--vp-c-divider);
}

.pause:hover {
  color: var(--vp-c-text-1);
}

.pause:focus-visible {
  outline: 3px solid var(--vp-c-brand-1);
  outline-offset: 2px;
}

/* ---------- 手机 ---------- */

.phone {
  --p-bg: #f2f2f7;
  --p-card: #fff;
  --p-fill: rgba(120, 120, 128, 0.16);
  --p-text: #000;
  --p-text-2: rgba(60, 60, 67, 0.6);
  --p-sep: rgba(60, 60, 67, 0.16);
  --p-tint: #007aff;
  --p-orange: #f08700;
  --p-glass: rgba(255, 255, 255, 0.72);

  flex: none;
  width: min(316px, 100%);
  padding: 8px;
  border-radius: 54px;
  background: #11161f;
  box-shadow:
    inset 0 0 0 1.5px #3a4352,
    0 40px 80px -40px rgba(15, 37, 71, 0.55);
}

.dark .phone {
  --p-bg: #000;
  --p-card: #1c1c1e;
  --p-fill: rgba(120, 120, 128, 0.28);
  --p-text: #fff;
  --p-text-2: rgba(235, 235, 245, 0.6);
  --p-sep: rgba(84, 84, 88, 0.55);
  --p-tint: #0a84ff;
  --p-orange: #ff9f0a;
  --p-glass: rgba(37, 37, 40, 0.72);

  box-shadow:
    inset 0 0 0 1.5px #3a4352,
    0 0 0 1px rgba(124, 192, 255, 0.12);
}

.phone.is-shaking {
  animation: shake 0.56s ease-in-out;
}

@keyframes shake {
  20% {
    transform: rotate(-4deg) translateX(-6px);
  }
  40% {
    transform: rotate(3.5deg) translateX(6px);
  }
  60% {
    transform: rotate(-3deg) translateX(-4px);
  }
  80% {
    transform: rotate(2deg) translateX(3px);
  }
}

.screen {
  position: relative;
  height: 644px;
  border-radius: 46px;
  overflow: hidden;
  background: var(--p-bg);
  color: var(--p-text);
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Helvetica Neue', 'Microsoft YaHei', sans-serif;
  font-size: 13px;
  line-height: 1.3;
  -webkit-font-smoothing: antialiased;
  user-select: none;
}

.screen svg {
  fill: currentColor;
}

.island {
  position: absolute;
  z-index: 20;
  top: 10px;
  left: 50%;
  width: 92px;
  height: 27px;
  border-radius: 14px;
  background: #000;
  transform: translateX(-50%);
}

.status {
  position: absolute;
  z-index: 19;
  inset: 0 0 auto;
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 46px;
  padding: 0 26px 0 34px;
  font-size: 14px;
  font-weight: 600;
}

.status-icons {
  display: flex;
  gap: 6px;
}

.status-icons svg:first-child {
  width: 16px;
  height: 11px;
}

.status-icons svg:last-child {
  width: 25px;
  height: 12px;
}

/* ---------- 页面通用 ---------- */

.scr {
  position: absolute;
  inset: 0;
  padding: 50px 14px 0;
  background: var(--p-bg);
}

.push-enter-active,
.push-leave-active,
.pop-enter-active,
.pop-leave-active {
  transition:
    transform 0.46s cubic-bezier(0.2, 0.85, 0.25, 1),
    filter 0.46s ease;
}

.push-enter-active,
.pop-leave-active {
  z-index: 2;
  box-shadow: -12px 0 24px -16px rgba(0, 0, 0, 0.35);
}

.push-enter-from,
.pop-leave-to {
  transform: translateX(100%);
}

.push-leave-to,
.pop-enter-from {
  transform: translateX(-28%);
  filter: brightness(0.92);
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.25s ease;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  height: 36px;
}

.toolbar.is-end {
  justify-content: flex-end;
}

.round,
.capsule {
  display: grid;
  place-items: center;
  height: 32px;
  border-radius: 16px;
  background: var(--p-glass);
  box-shadow:
    0 1px 3px rgba(0, 0, 0, 0.08),
    inset 0 0 0 0.5px var(--p-sep);
}

.round {
  width: 32px;
}

.round svg {
  width: 18px;
  height: 18px;
}

.capsule {
  padding: 0 12px;
  font-size: 13px;
}

.capsule.is-dim {
  color: var(--p-text-2);
}

.large-title {
  margin: 4px 2px 8px;
  font-size: 27px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: 0;
}

.card {
  border-radius: 18px;
  background: var(--p-card);
}

.group-title {
  margin: 12px 14px 5px;
  font-size: 12px;
  color: var(--p-text-2);
}

.group-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}

.group-head .link {
  margin-right: 14px;
  padding: 2px 4px;
  border-radius: 6px;
  font-size: 12px;
}

.footnote {
  margin: 6px 14px 0;
  font-size: 11px;
  color: var(--p-text-2);
}

.link {
  color: var(--p-tint);
  transition: opacity 0.2s ease;
}

.is-pressed {
  transform: scale(0.94);
  filter: brightness(0.94);
  transition: transform 0.12s ease;
}

/* ---------- 做菜 ---------- */

.search {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 10px;
  border-radius: 17px;
  background: var(--p-fill);
  color: var(--p-text-2);
  white-space: nowrap;
  overflow: hidden;
  transition: box-shadow 0.2s ease;
}

.search.is-focused {
  box-shadow: inset 0 0 0 1.5px var(--p-tint);
}

.search svg {
  flex: none;
  width: 16px;
  height: 16px;
}

.search-placeholder {
  overflow: hidden;
  text-overflow: ellipsis;
}

.search-text {
  display: flex;
  align-items: center;
  color: var(--p-text);
  direction: rtl;
  overflow: hidden;
  unicode-bidi: plaintext;
}

.caret {
  flex: none;
  width: 2px;
  height: 17px;
  margin-left: 1px;
  border-radius: 1px;
  background: var(--p-tint);
  animation: blink 1s steps(1) infinite;
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.notice {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  padding: 9px 12px;
  font-size: 12px;
}

.notice svg {
  flex: none;
  width: 16px;
  height: 16px;
  color: var(--p-tint);
}

.notice-enter-active {
  transition:
    opacity 0.3s ease,
    transform 0.3s cubic-bezier(0.2, 0.85, 0.25, 1);
}

.notice-enter-from {
  opacity: 0;
  transform: translateY(-8px);
}

.chip-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
  padding: 9px;
}

.chip {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 4px;
  height: 29px;
  border-radius: 15px;
  background: var(--p-fill);
  font-size: 12.5px;
  white-space: nowrap;
  transition:
    background-color 0.18s ease,
    color 0.18s ease,
    transform 0.12s ease;
}

.chip i {
  font-style: normal;
  font-size: 13px;
}

.chip.is-on {
  background: var(--p-tint);
  color: #fff;
}

.see-wrap {
  position: absolute;
  inset: auto 0 76px;
  padding: 26px 14px 0;
  background: linear-gradient(to bottom, transparent, var(--p-bg) 40%);
}

.see {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  height: 44px;
  border-radius: 22px;
  background: var(--p-tint);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
}

.see.is-off {
  background: var(--p-fill);
  color: var(--p-text-2);
}

.see.is-bounce {
  animation: bounce 0.35s cubic-bezier(0.3, 1.6, 0.5, 1);
}

@keyframes bounce {
  30% {
    transform: scale(1.06);
  }
}

.count {
  font-variant-numeric: tabular-nums;
}

.basket {
  display: flex;
}

.basket i {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  margin-left: -9px;
  border-radius: 50%;
  background: var(--p-card);
  box-shadow: 0 0 0 1px rgba(255, 255, 255, 0.6);
  font-size: 14px;
  font-style: normal;
  animation: pop 0.35s cubic-bezier(0.3, 1.6, 0.5, 1);
}

.basket i:first-child {
  margin-left: 0;
}

@keyframes pop {
  from {
    transform: scale(0.3);
    opacity: 0;
  }
}

/* ---------- 列表 ---------- */

.cell {
  display: flex;
  justify-content: space-between;
  padding: 11px 14px;
}

.secondary {
  color: var(--p-text-2);
}

.row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 52px;
  padding: 6px 12px;
  animation: row-in 0.42s cubic-bezier(0.2, 0.85, 0.25, 1) both;
  animation-delay: calc(var(--i, 0) * 55ms + 120ms);
}

.row + .row {
  box-shadow: inset 0 0.5px 0 var(--p-sep);
}

@keyframes row-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
}

.row-emoji {
  flex: none;
  width: 34px;
  font-size: 26px;
  text-align: center;
}

.row-emoji.is-small {
  font-size: 22px;
}

.row-body {
  display: grid;
  flex: 1;
  min-width: 0;
}

.row-body b {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.row-body small {
  font-size: 11.5px;
  color: var(--p-text-2);
}

.row-body .missing {
  color: var(--p-orange);
}

.row-next {
  flex: none;
  width: 16px;
  height: 16px;
  color: var(--p-text-2);
  opacity: 0.6;
}

.expiry {
  font-size: 12.5px;
  color: var(--p-text-2);
  font-variant-numeric: tabular-nums;
}

.expiry.is-soon {
  color: var(--p-orange);
}

/* ---------- 随机 ---------- */

.scr-random {
  display: flex;
  flex-direction: column;
}

.rand-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin: 70px 4px 0;
  padding: 28px 16px 24px;
  border-radius: 26px;
  background: var(--p-card);
  box-shadow: 0 8px 24px -10px rgba(0, 0, 0, 0.18);
  text-align: center;
}

.rand-emoji {
  display: block;
  font-size: 76px;
  line-height: 1.1;
}

.rand-emoji.is-toss {
  animation: toss 0.62s ease-out;
}

@keyframes toss {
  20% {
    transform: translateY(-18px) rotate(-14deg) scale(1.12);
  }
  45% {
    transform: translateY(-10px) rotate(12deg) scale(1.08);
  }
  70% {
    transform: translateY(-2px) rotate(-6deg) scale(1.02);
  }
}

.rand-name {
  margin-top: 6px;
  font-size: 21px;
  font-weight: 700;
}

.rand-card small {
  font-size: 12.5px;
  color: var(--p-text-2);
}

.rand-card .link {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
}

.is-redacted {
  border-radius: 6px;
  background: var(--p-fill);
  color: transparent !important;
}

.roll {
  position: absolute;
  inset: auto 14px 90px;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 6px;
  height: 44px;
  border-radius: 22px;
  background: var(--p-tint);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
}

.roll svg {
  width: 18px;
  height: 18px;
}

/* ---------- 通知、标签栏、触点 ---------- */

.banner {
  position: absolute;
  z-index: 21;
  inset: 44px 8px auto;
  display: flex;
  gap: 10px;
  padding: 12px 13px;
  border-radius: 22px;
  background: var(--p-glass);
  backdrop-filter: blur(24px) saturate(1.6);
  -webkit-backdrop-filter: blur(24px) saturate(1.6);
  box-shadow: 0 10px 30px -8px rgba(0, 0, 0, 0.3);
  font-size: 12.5px;
}

.banner img {
  flex: none;
  border-radius: 5px;
}

.banner p {
  margin: 0;
}

.banner-head {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.banner-head span {
  color: var(--p-text-2);
  font-size: 11px;
}

.banner-enter-active,
.banner-leave-active {
  transition:
    transform 0.5s cubic-bezier(0.2, 0.9, 0.25, 1.1),
    opacity 0.35s ease;
}

.banner-enter-from,
.banner-leave-to {
  opacity: 0;
  transform: translateY(-120%);
}

.tabbar {
  position: absolute;
  z-index: 10;
  inset: auto 12px 18px;
  display: flex;
  justify-content: space-between;
  padding: 4px;
  border-radius: 30px;
  background: var(--p-glass);
  backdrop-filter: blur(20px) saturate(1.6);
  -webkit-backdrop-filter: blur(20px) saturate(1.6);
  box-shadow:
    0 6px 20px -8px rgba(0, 0, 0, 0.25),
    inset 0 0 0 0.5px var(--p-sep);
}

.tab {
  display: grid;
  justify-items: center;
  gap: 1px;
  flex: 1;
  padding: 5px 0 4px;
  border-radius: 24px;
  color: var(--p-text);
  transition:
    background-color 0.25s ease,
    color 0.25s ease;
}

.tab svg {
  width: 22px;
  height: 22px;
}

.tab small {
  font-size: 10px;
}

.tab.is-on {
  background: var(--p-fill);
  color: var(--p-tint);
}

.touch {
  position: absolute;
  z-index: 40;
  width: 40px;
  height: 40px;
  margin: -20px 0 0 -20px;
  border-radius: 50%;
  background: rgba(120, 120, 128, 0.35);
  box-shadow: 0 0 0 1.5px rgba(255, 255, 255, 0.7);
  pointer-events: none;
  animation: touch 0.7s ease-out both;
}

@keyframes touch {
  0% {
    opacity: 0;
    transform: scale(1.5);
  }
  25% {
    opacity: 1;
    transform: scale(0.85);
  }
  45% {
    opacity: 1;
    transform: scale(0.85);
  }
  100% {
    opacity: 0;
    transform: scale(1.1);
  }
}

@media (max-width: 380px) {
  .phone {
    width: min(300px, 100%);
    padding: 6px;
  }
}

.demo.is-paused :deep(*) {
  animation-play-state: paused !important;
}

@media (prefers-reduced-motion: reduce) {
  *,
  *::before,
  *::after {
    animation: none !important;
    transition: none !important;
  }
  .row,
  .see.is-bounce,
  .basket i,
  .rand-emoji.is-toss,
  .phone.is-shaking,
  .caret {
    animation: none;
  }
}
</style>
