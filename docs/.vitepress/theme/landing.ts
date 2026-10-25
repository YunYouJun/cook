import type { RecipeItem } from '../../../packages/cook/src/types/recipe'
import type { CookQuery, RecommendationOptions } from '../../../packages/cook/src/utils/recommend'
import { createRecipeRecommender, uniqueStuff } from '../../../packages/cook/src/utils/recommend'
import food from '../../../packages/data/food.json'
import pantry from '../../../packages/data/pantry.json'
import { data } from './recipes.data'

/** 首页用到的菜谱、推荐和展示规则，和 App 的 RecipeStore 对齐 */

export interface StuffItem {
  name: string
  emoji: string
  more?: boolean
}

export const total = data.total

/** 和 App 做菜页一样：常用食材分组，不含「更多」里收起的 */
export const stuffGroups = [
  { title: '蔬菜', items: (food.vegetable as StuffItem[]).filter(item => !item.more) },
  { title: '肉和蛋', items: (food.meat as StuffItem[]).filter(item => !item.more) },
  { title: '主食', items: (food.staple as StuffItem[]).filter(item => !item.more) },
]

const allStuff = [...food.vegetable, ...food.meat, ...food.staple] as StuffItem[]
const emojiByName = new Map(allStuff.map(item => [item.name, item.emoji]))

export const catalog: RecipeItem[] = data.recipes.map(recipe => ({
  id: recipe.id,
  name: recipe.n,
  stuff: recipe.s,
  difficulty: recipe.d as RecipeItem['difficulty'],
  methods: recipe.m,
  tools: recipe.t ?? [],
  score: recipe.c,
}))

const recommender = createRecipeRecommender(catalog)

/** 默认家里有油和盐，和 App 首次打开时一样 */
export function recommend(options: RecommendationOptions) {
  return recommender({ pantry: pantry.defaults, ...options })
}

export function emojiOf(name: string): string | undefined {
  return emojiByName.get(name) || undefined
}

/** 列表里代表这道菜的 emoji：大杂烩用锅，其余用第一样有 emoji 的食材 */
export function thumbnail(recipe: RecipeItem): string {
  const stuff = uniqueStuff(recipe.stuff)
  if (stuff.length >= 10)
    return '🍲'
  for (const name of stuff) {
    const emoji = emojiOf(name)
    if (emoji)
      return emoji
  }
  return '🍽️'
}

export function toolLabel(tool: string) {
  return tool === '一口大锅' ? '炒锅' : tool
}

/** 厨具 · 难度 */
export function meta(recipe: RecipeItem) {
  const tools = recipe.tools.length ? recipe.tools.map(toolLabel).join(' / ') : '厨具未记录'
  return recipe.difficulty ? `${tools} · ${recipe.difficulty}` : tools
}

/** 一句话识别后的提示，和 App 的 RecipeStore.describe 一致 */
export function describe(query: CookQuery) {
  const parts = [[...query.stuff, ...query.unknown].join('、')]
  if (query.avoid.length)
    parts.push(`忌口${query.avoid.join('、')}`)
  if (query.noSpicy)
    parts.push('不辣')
  if (query.tool)
    parts.push(toolLabel(query.tool))
  parts.push(...query.methodGroups, ...query.difficulty)
  return parts.filter(Boolean).join(' · ')
}

/** 按缺几样分组，和 App 结果页的分组名一致 */
export const groupLabels = ['齐全', '差一种', '差两种']

/**
 * 「飞进篮子」：沿一条先上扬再落下的二次贝塞尔弧线飞过去，最后三分之一才变小变淡。
 * 和 App 的 BasketFlight 同一条曲线；坐标都相对 layer。
 */
export function flyEmoji(layer: HTMLElement, label: string, from: DOMRect, to: DOMRect, options: { size?: number, duration?: number, lift?: number, signal?: AbortSignal } = {}): Promise<void> {
  if (options.signal?.aborted || prefersReducedMotion() || typeof layer.animate !== 'function')
    return Promise.resolve()
  const base = layer.getBoundingClientRect()
  // 外层被 transform 缩放时，getBoundingClientRect 是缩放后的尺寸
  const scale = base.width / (layer.offsetWidth || base.width)
  const point = (rect: DOMRect) => ({
    x: (rect.left + rect.width / 2 - base.left) / scale,
    y: (rect.top + rect.height / 2 - base.top) / scale,
  })
  const start = point(from)
  const end = point(to)
  const control = { x: (start.x + end.x) / 2, y: Math.min(start.y, end.y) - (options.lift ?? 90) }

  const el = document.createElement('span')
  el.textContent = label
  el.setAttribute('aria-hidden', 'true')
  Object.assign(el.style, {
    position: 'absolute',
    left: '0',
    top: '0',
    fontSize: `${options.size ?? 28}px`,
    lineHeight: '1',
    pointerEvents: 'none',
    zIndex: '30',
    willChange: 'transform, opacity',
  })
  layer.appendChild(el)

  const frames: Keyframe[] = []
  for (let i = 0; i <= 16; i++) {
    const t = i / 16
    const x = (1 - t) ** 2 * start.x + 2 * (1 - t) * t * control.x + t ** 2 * end.x
    const y = (1 - t) ** 2 * start.y + 2 * (1 - t) * t * control.y + t ** 2 * end.y
    const fade = Math.max(0, t - 0.65) / 0.35
    frames.push({
      offset: t,
      transform: `translate(${x}px, ${y}px) translate(-50%, -50%) scale(${1.2 - 0.25 * t - 0.4 * fade})`,
      opacity: 1 - 0.7 * fade,
    })
  }
  const animation = el.animate(frames, { duration: options.duration ?? 450, easing: 'cubic-bezier(0.25, 0.1, 0.25, 1)', fill: 'forwards' })
  const cancel = () => animation.cancel()
  options.signal?.addEventListener('abort', cancel, { once: true })
  const cleanup = () => {
    options.signal?.removeEventListener('abort', cancel)
    el.remove()
  }
  return animation.finished.then(cleanup, cleanup)
}

/** 数字滚动到目标值，像 App 里的 numericText；减弱动态效果时直接跳 */
export function tweenNumber(from: number, to: number, set: (value: number) => void, duration = 420) {
  if (from === to || typeof requestAnimationFrame === 'undefined') {
    set(to)
    return () => {}
  }
  const start = performance.now()
  let frame = 0
  const step = (now: number) => {
    const p = Math.min(1, (now - start) / duration)
    set(Math.round(from + (to - from) * (1 - (1 - p) ** 3)))
    if (p < 1)
      frame = requestAnimationFrame(step)
  }
  frame = requestAnimationFrame(step)
  return () => cancelAnimationFrame(frame)
}

export function prefersReducedMotion() {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
