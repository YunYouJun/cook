/**
 * 云乐坊 App 内嵌容器（apps.yunle.fun）注入的 `window.ylf` JSAPI。
 * 普通浏览器与 Cook 自己的 Capacitor 外壳中不存在，所有调用都要特性检测。
 *
 * @see https://github.com/YunLeFun/apps.yunle.fun/blob/main/docs/guide/jsapi.md
 */

export type YunleDisplayMode = 'immersive' | 'titlebar'
export type YunleHapticStyle = 'light' | 'medium' | 'heavy' | 'success'

export interface YunleInsets {
  top: number
  bottom: number
  left: number
  right: number
}

export interface YunleRect {
  x: number
  y: number
  width: number
  height: number
}

export interface YunleHostEnv {
  platform: 'ios' | 'android' | 'macos'
  appId: string
  /** 旧版本 App 无此字段，按 immersive 处理 */
  displayMode?: YunleDisplayMode
  trusted: boolean
  capsuleVisible: boolean
  titleBarVisible?: boolean
  safeAreaInsets: YunleInsets
  /** Desktop hosts omit the rectangle when no capsule is available. */
  capsuleRect?: YunleRect
}

export interface YunleJSAPI {
  version: number
  inYunleApp: true
  canIUse: (method: string) => Promise<boolean>
  getEnv: () => Promise<YunleHostEnv>
  haptic?: (style: YunleHapticStyle) => Promise<void>
}

declare global {
  interface Window {
    ylf?: YunleJSAPI
  }
}

type HostWindow = Pick<Window, 'ylf' | 'document' | 'addEventListener' | 'setTimeout'>

/** 胶囊左侧与页面内容之间留的间距 */
const CAPSULE_GAP = 8
/** Android 未能在文档开始时注入时，宿主会在页面加载完成后补注入 */
const LATE_INJECTION_DELAY = 300

export function getYunleHost(win: Pick<Window, 'ylf'> | undefined = typeof window === 'undefined' ? undefined : window): YunleJSAPI | undefined {
  const host = win?.ylf
  return host?.inYunleApp === true && typeof host.getEnv === 'function' ? host : undefined
}

/**
 * 等待宿主注入：先同步检测，否则在 load 之后再检测一次
 */
export function waitForYunleHost(win: HostWindow | undefined = typeof window === 'undefined' ? undefined : window): Promise<YunleJSAPI | undefined> {
  if (!win)
    return Promise.resolve(undefined)
  const host = getYunleHost(win)
  if (host)
    return Promise.resolve(host)

  return new Promise((resolve) => {
    const check = () => win.setTimeout(() => resolve(getYunleHost(win)), LATE_INJECTION_DELAY)
    if (win.document.readyState === 'complete')
      check()
    else
      win.addEventListener('load', check, { once: true })
  })
}

export function resolveDisplayMode(env: Pick<YunleHostEnv, 'displayMode'>): YunleDisplayMode {
  return env.displayMode === 'titlebar' ? 'titlebar' : 'immersive'
}

/**
 * 胶囊在视口内时，页面右侧需要让出的宽度；标题栏显示时胶囊在视口上方（y 为负），无需避让
 */
export function capsuleInset(env: Pick<YunleHostEnv, 'capsuleVisible' | 'capsuleRect'>, viewportWidth: number): number {
  if (!env.capsuleVisible || !env.capsuleRect)
    return 0
  const { x, y, width, height } = env.capsuleRect
  if (width <= 0 || y + height <= 0)
    return 0
  return Math.max(0, Math.ceil(viewportWidth - x + CAPSULE_GAP))
}

/**
 * 把宿主环境转换为页面使用的 CSS 变量：安全区交给 Ionic，胶囊避让交给工具栏
 */
export function hostLayoutVars(env: YunleHostEnv, viewportWidth: number): Record<string, string> {
  const px = (value: number) => `${Math.max(0, Math.round(value || 0))}px`
  const { top, bottom, left, right } = env.safeAreaInsets
  return {
    '--ion-safe-area-top': px(top),
    '--ion-safe-area-bottom': px(bottom),
    '--ion-safe-area-left': px(left),
    '--ion-safe-area-right': px(right),
    '--cook-host-capsule-inset': px(capsuleInset(env, viewportWidth)),
  }
}

export const YUNLE_HOST_CLASS = 'in-yunle-app'

/**
 * 在根元素上标记宿主形态并写入布局变量
 */
export function applyYunleHostLayout(root: HTMLElement, env: YunleHostEnv, viewportWidth: number) {
  const mode = resolveDisplayMode(env)
  root.classList.add(YUNLE_HOST_CLASS)
  root.classList.toggle('ylf-mode-immersive', mode === 'immersive')
  root.classList.toggle('ylf-mode-titlebar', mode === 'titlebar')
  for (const [name, value] of Object.entries(hostLayoutVars(env, viewportWidth)))
    root.style.setProperty(name, value)
}

let hapticSupport: Promise<boolean> | undefined

/**
 * 宿主触感反馈；未授信或旧版本 App 时静默跳过
 */
export async function yunleHaptic(style: YunleHapticStyle = 'light') {
  const host = getYunleHost()
  if (!host?.haptic)
    return
  hapticSupport ??= host.canIUse('haptic').catch(() => false)
  if (await hapticSupport)
    await host.haptic(style).catch(() => {})
}

/** 仅供测试重置缓存 */
export function resetYunleHostCache() {
  hapticSupport = undefined
}
