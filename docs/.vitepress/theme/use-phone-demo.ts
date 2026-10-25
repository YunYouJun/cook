import type { ShallowRef } from 'vue'
import type { RecipeItem } from '../../../packages/cook/src/types/recipe'
import type { RecipeMatch } from '../../../packages/cook/src/utils/recommend'
import { computed, nextTick, onBeforeUnmount, onMounted, shallowRef, watch } from 'vue'
import { parseCookQuery } from '../../../packages/cook/src/utils/recommend'
import { catalog, describe, emojiOf, flyEmoji, groupLabels, prefersReducedMotion, recommend, thumbnail, tweenNumber } from './landing'

type Screen = 'cook' | 'results' | 'fridge' | 'random'
type ChapterId = 'pick' | 'sentence' | 'fridge' | 'random'
interface PlaybackContext {
  fast: boolean
  wait: (ms: number) => Promise<void>
}

const SENTENCE = '冰箱里有半颗白菜两个蛋，不要辣'
const PICKS = ['土豆', '番茄', '鸡蛋']
// Storage ages match the refrigerator estimates in shelf-life.json.
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

/** Own scene state and cancelable playback for the homepage phone demonstration. */
export function usePhoneDemo(root: Readonly<ShallowRef<HTMLElement | null>>, screenEl: Readonly<ShallowRef<HTMLElement | null>>) {
  // Favor simple recipes with well-rated videos for the random scene.
  const preferredPool: RecipeItem[] = catalog.filter(recipe => (recipe.score ?? 0) >= 70 && recipe.difficulty === '简单' && thumbnail(recipe) !== '🍽️')

  // Scene state

  const pool = preferredPool.length ? preferredPool : catalog

  const screen = shallowRef<Screen>('cook')
  const transitionName = shallowRef('fade')
  const resultsFrom = shallowRef<'cook' | 'fridge'>('cook')
  const tab = computed(() => screen.value === 'results' ? resultsFrom.value : screen.value)

  const selected = shallowRef<string[]>([])
  const basket = shallowRef<string[]>([])
  const noSpicy = shallowRef(false)
  const query = shallowRef('')
  const searching = shallowRef(false)
  const notice = shallowRef('')
  const bounceKey = shallowRef(0)
  const displayCount = shallowRef(0)

  const resultStuff = shallowRef<string[]>([])
  const banner = shallowRef(false)
  const card = shallowRef<RecipeItem>(pool[0]!)
  const shuffleEmoji = shallowRef('')
  const tossKey = shallowRef(0)
  const shaking = shallowRef(false)
  const touch = shallowRef({ x: 0, y: 0, n: 0 })
  const today = shallowRef<Date>()

  const active = shallowRef(0)
  const playback = shallowRef<'idle' | 'playing' | 'paused' | 'finished'>('idle')
  const running = computed(() => playback.value === 'playing')
  const userPaused = shallowRef(true)
  const reduced = shallowRef(false)
  const progressKey = shallowRef(0)

  const cookCount = computed(() => selected.value.length ? recommend({ stuff: selected.value, noSpicy: noSpicy.value }).length : 0)

  const resultMatches = computed(() => resultStuff.value.length ? recommend({ stuff: resultStuff.value, noSpicy: noSpicy.value }) : [])
  // Keep the same completeness groups as the native results list.
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

  // Playback

  let runId = 0
  let playbackController = new AbortController()

  function createPlaybackContext(fast: boolean): PlaybackContext {
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
    touch.value = { x: 0, y: 0, n: 0 }
    go(id === 'fridge' ? 'fridge' : id === 'random' ? 'random' : 'cook')
  }

  function find(key: string) {
    return screenEl.value?.querySelector<HTMLElement>(`[data-demo="${key}"]`) ?? undefined
  }

  // Show touch feedback inside the decorative phone screen.
  async function tap(ctx: PlaybackContext, key: string) {
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

  // Only manual chip selection uses the native-style basket flight.
  function pick(ctx: PlaybackContext, name: string) {
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
  async function roll(ctx: PlaybackContext) {
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

  const scripts: Record<ChapterId, (ctx: PlaybackContext) => Promise<void>> = {
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
      // Submitting a sentence applies the whole query at once, like CookView.
      // The native app reserves ingredient flights for manual chip selection.
      selected.value = parsed.stuff
      basket.value = parsed.stuff.slice(-3)
      noSpicy.value = parsed.noSpicy
      notice.value = `已按「${describe(parsed)}」筛选`
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
    playback.value = reduced.value ? 'finished' : 'playing'
    progressKey.value++
    const ctx = createPlaybackContext(reduced.value)
    try {
      await nextTick()
      if (id !== runId)
        return
      await Promise.all([scripts[chapter.id](ctx), ctx.wait(chapter.duration)])
      if (id === runId) {
        playback.value = 'finished'
        userPaused.value = true
      }
    }
    catch (error) {
      if (id !== runId)
        return
      playback.value = 'paused'
      userPaused.value = true
      throw error
    }
  }

  function stop() {
    cancel()
    if (running.value)
      playback.value = 'paused'
    // Remove transient feedback so a stopped chapter remains legible.
    shuffleEmoji.value = ''
    shaking.value = false
    touch.value = { x: 0, y: 0, n: 0 }
    basket.value = selected.value.slice(-3)
    displayCount.value = cookCount.value
  }

  let visible = true
  let observer: IntersectionObserver | undefined

  // Restart the selected chapter when it returns to view, unless explicitly paused.
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

  return {
    chapters,
    SOON,
    FRESH,
    active,
    running,
    playback,
    reduced,
    progressKey,
    screen,
    transitionName,
    tab,
    selected,
    basket,
    searching,
    query,
    notice,
    bounceKey,
    displayCount,
    resultStuff,
    noSpicy,
    resultGroups,
    banner,
    card,
    shuffleEmoji,
    tossKey,
    shaking,
    touch,
    addedText,
    choose,
    togglePause,
  }
}
