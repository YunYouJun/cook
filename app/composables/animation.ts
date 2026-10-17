import type { MaybeElementRef } from '@vueuse/core'
import { isClient, unrefElement } from '@vueuse/core'

const FLIGHT_DURATION = 560
const FLIGHT_STEPS = 16
/** 抛物线顶点相对起点额外抬高的距离 */
const FLIGHT_LIFT = 72

function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}

function center(rect: DOMRect) {
  return { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }
}

/**
 * 篮子按钮“接住”食材的回弹
 */
function bump(el: Element) {
  el.animate([
    { transform: 'scale(1)' },
    { transform: 'scale(1.18)', offset: 0.35 },
    { transform: 'scale(0.94)', offset: 0.7 },
    { transform: 'scale(1)' },
  ], { duration: 420, easing: 'ease-out' })
}

/**
 * 选中食材时，emoji 沿抛物线飞进篮子按钮
 */
export function useEmojiAnimation(basket: MaybeElementRef) {
  const playAnimation = (emoji: string, origin?: Element | null) => {
    if (!isClient)
      return

    const basketEl = unrefElement(basket)
    // 落点优先用标记了 data-flight-target 的元素，其次是容器里真正的按钮
    const target = basketEl?.querySelector('[data-flight-target], ion-fab-button, button') ?? basketEl
    if (!target?.isConnected)
      return

    const to = target.getBoundingClientRect()
    if (!to.width || !to.height)
      return

    if (!origin || prefersReducedMotion()) {
      if (!prefersReducedMotion())
        bump(target)
      return
    }

    const start = center(origin.getBoundingClientRect())
    const end = center(to)
    const dx = end.x - start.x
    const dy = end.y - start.y
    // 二次贝塞尔控制点：水平居中，竖直方向高于起点与终点
    const cx = dx / 2
    const cy = Math.min(0, dy) - FLIGHT_LIFT

    const keyframes: Keyframe[] = []
    for (let i = 0; i <= FLIGHT_STEPS; i++) {
      const t = i / FLIGHT_STEPS
      const x = 2 * (1 - t) * t * cx + t * t * dx
      const y = 2 * (1 - t) * t * cy + t * t * dy
      const scale = t < 0.2 ? 1 + t * 1.5 : 1.3 - (t - 0.2) * 1.1
      keyframes.push({
        transform: `translate(-50%, -50%) translate(${x}px, ${y}px) rotate(${t * (dx >= 0 ? 30 : -30)}deg) scale(${scale})`,
        opacity: t > 0.85 ? 1 - (t - 0.85) / 0.15 * 0.7 : 1,
      })
    }

    const emojiEl = document.createElement('span')
    emojiEl.textContent = emoji
    emojiEl.setAttribute('aria-hidden', 'true')
    Object.assign(emojiEl.style, {
      position: 'fixed',
      left: `${start.x}px`,
      top: `${start.y}px`,
      zIndex: '1000',
      fontSize: '20px',
      lineHeight: '1',
      pointerEvents: 'none',
      willChange: 'transform, opacity',
    })
    document.body.appendChild(emojiEl)

    const flight = emojiEl.animate(keyframes, {
      duration: FLIGHT_DURATION,
      easing: 'cubic-bezier(0.45, 0, 0.75, 1)',
      fill: 'forwards',
    })
    flight.finished.then(() => bump(target), () => {}).finally(() => emojiEl.remove())
  }

  return {
    playAnimation,
  }
}
