import type { VideoStatus } from '../../packages/cook/src/utils/bilibili'
import { setTimeout as sleep } from 'node:timers/promises'
import { bilibiliViewApi, classifyBilibiliCode } from '../../packages/cook/src/utils/bilibili'

const VIDEO_ID_RE = /^(?:BV[0-9A-Za-z]{10}|av\d+)$/
export const MAX_VIDEOS = 20
export const DELAY_MS = 5000
const DAY_MS = 24 * 60 * 60 * 1000
const CACHE_MS = 7 * DAY_MS

interface RecipeVideo { id: number, name: string, bv?: string }
interface Confirmed { status: 'ok' | 'dead', checkedAt: string, detail: string }
export interface AuditState {
  trusted: Record<string, Confirmed>
  cooldownUntil?: string
}
interface CheckResult { status: VideoStatus, detail: string, blocked?: boolean }
interface Entry {
  id: string
  uses: string[]
  status: VideoStatus | 'unchecked'
  detail: string
  checkedAt?: string
  cached?: boolean
}
interface Audit {
  online: boolean
  generatedAt: string
  selected: string[]
  entries: Entry[]
  state: AuditState
}

export function changedVideoIds(current: RecipeVideo[], base: RecipeVideo[]) {
  const previous = new Map(base.map(recipe => [recipe.id, recipe.bv]))
  return [...new Set(current.filter(recipe => recipe.bv && previous.get(recipe.id) !== recipe.bv).map(recipe => recipe.bv!))]
}

export function selectVideoIds(recipes: RecipeVideo[], requested: string[] | undefined, online: boolean) {
  const catalog = new Set(recipes.map(recipe => recipe.bv).filter((id): id is string => !!id))
  for (const id of catalog) {
    if (!VIDEO_ID_RE.test(id))
      throw new Error(`视频号格式错误：${id}`)
  }
  if (online && requested === undefined)
    throw new Error('联网必须显式指定 --ids 或 --changed；不提供全量扫描。')
  const ids = [...new Set(requested ?? [])]
  if (ids.some(id => !catalog.has(id)))
    throw new Error('所选视频必须存在于当前 recipe.csv。')
  if (online && ids.length > MAX_VIDEOS)
    throw new Error(`每次最多复核 ${MAX_VIDEOS} 个视频，请缩小范围。`)
  return ids
}

export async function checkVideo(id: string, request: typeof fetch = fetch): Promise<CheckResult> {
  try {
    const response = await request(bilibiliViewApi(id), {
      headers: { 'User-Agent': 'cook recipe link checker (+https://github.com/YunYouJun/cook)' },
      signal: AbortSignal.timeout(15_000),
      redirect: 'error',
    })
    if (!response.ok)
      return { status: 'unknown', detail: `HTTP ${response.status}`, blocked: response.status === 412 || response.status === 429 }
    const body = await response.json() as { code?: number, message?: string, data?: { bvid?: string, aid?: number } }
    // code=0 还需对应视频数据，避免空/错误响应被当成可用。
    if (body.code === 0 && (id.startsWith('av') ? String(body.data?.aid) !== id.slice(2) : body.data?.bvid !== id))
      return { status: 'unknown', detail: '响应缺少匹配的视频数据' }
    return {
      status: classifyBilibiliCode(body.code ?? Number.NaN),
      detail: `${body.code ?? 'missing code'} ${body.message ?? ''}`.trim(),
      blocked: body.code === -412 || body.code === -429,
    }
  }
  catch (error) {
    return { status: 'unknown', detail: error instanceof Error ? error.message : String(error) }
  }
}

export async function auditVideos(recipes: RecipeVideo[], options: {
  online: boolean
  ids: string[]
  state?: AuditState
  request?: typeof fetch
  wait?: (ms: number) => Promise<unknown>
  now?: () => number
}): Promise<Audit> {
  const now = options.now ?? Date.now
  const state: AuditState = structuredClone(options.state ?? { trusted: {} })
  if (!state.trusted || typeof state.trusted !== 'object' || Array.isArray(state.trusted)
    || (state.cooldownUntil !== undefined && !Number.isFinite(Date.parse(state.cooldownUntil)))
    || Object.values(state.trusted).some(item => !item || !['ok', 'dead'].includes(item.status) || !Number.isFinite(Date.parse(item.checkedAt)) || typeof item.detail !== 'string')) {
    throw new Error('巡检状态文件格式错误，请人工核对；不会丢弃历史或冷却记录。')
  }
  const selected = selectVideoIds(recipes, options.ids, options.online)
  const byVideo = new Map<string, Entry>()
  for (const recipe of recipes) {
    if (!recipe.bv)
      continue
    const entry = byVideo.get(recipe.bv) ?? { id: recipe.bv, uses: [], status: 'unchecked', detail: options.online ? '未选择' : '离线静态校验，不代表在线可用' }
    entry.uses.push(`${recipe.id} ${recipe.name}`)
    byVideo.set(recipe.bv, entry)
  }
  let stopped = state.cooldownUntil && Date.parse(state.cooldownUntil) > now() ? `冷却至 ${state.cooldownUntil}` : ''
  let requests = 0
  let consecutiveUnknown = 0
  for (const id of selected) {
    const entry = byVideo.get(id)!
    if (!options.online)
      continue
    const previous = state.trusted[id]
    if (previous && now() >= Date.parse(previous.checkedAt) && now() - Date.parse(previous.checkedAt) < CACHE_MS) {
      entry.detail = '7 天内已有可信记录，本次未请求（历史结果见右列）'
      entry.cached = true
      continue
    }
    if (stopped) {
      entry.detail = stopped
      continue
    }
    if (requests)
      await (options.wait ?? sleep)(DELAY_MS)
    const result = await checkVideo(id, options.request)
    requests++
    Object.assign(entry, { status: result.status, detail: result.detail, checkedAt: new Date(now()).toISOString() })
    if (result.status !== 'unknown') {
      state.trusted[id] = { status: result.status, detail: result.detail, checkedAt: entry.checkedAt! }
      consecutiveUnknown = 0
    }
    else {
      consecutiveUnknown++
    }
    if (result.blocked || consecutiveUnknown >= 3) {
      state.cooldownUntil = new Date(now() + DAY_MS).toISOString()
      stopped = `${result.blocked ? '风控/限流' : '连续 3 次未知'}，停止请求；冷却至 ${state.cooldownUntil}`
    }
  }
  return { online: options.online, generatedAt: new Date(now()).toISOString(), selected, entries: [...byVideo.values()], state }
}

const labels = { ok: '可用', dead: '确认失效', unknown: '未知', unchecked: '未检查' }
function cell(value: string) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('|', '&#124;').replaceAll('`', '&#96;').replace(/[\r\n]/g, ' ')
}

export function renderVideoReport(audit: Audit) {
  const count = (status: Entry['status']) => audit.entries.filter(entry => entry.status === status).length
  const confirmed = count('ok') + count('dead')
  const attempted = confirmed + count('unknown')
  const ratio = (n: number, total: number) => total ? `${(100 * n / total).toFixed(1)}%` : '不适用（空范围）'
  const latest = Object.values(audit.state.trusted).map(item => item.checkedAt).sort().at(-1)
  const rows = audit.entries.filter(entry => audit.selected.includes(entry.id) || audit.state.trusted[entry.id])
  return [
    '# 视频巡检报告',
    '',
    `模式：${audit.online ? '本机显式按需复核' : '离线静态校验'}；报告生成时间：${audit.generatedAt}。`,
    `目录去重视频 ${audit.entries.length} 个；本次选择 ${audit.selected.length} 个，实际请求 ${attempted} 个。`,
    `本次：可用 ${count('ok')}，确认失效 ${count('dead')}，未知 ${count('unknown')}，未检查 ${count('unchecked')}。`,
    `可信覆盖率（本次确定结果 / 目录）：${confirmed}/${audit.entries.length} = ${ratio(confirmed, audit.entries.length)}；所选范围可信覆盖率：${confirmed}/${audit.selected.length} = ${ratio(confirmed, audit.selected.length)}。`,
    `最近一次可信检查日期：${latest ?? '无可信记录'}（逐视频日期见表；历史缓存不计入本次覆盖率）。`,
    '',
    ...(!audit.online
      ? ['> 本次仅校验本地视频号；未访问视频服务，不能判断在线可用性。']
      : confirmed < audit.selected.length
        ? ['> 巡检受阻或未完成：所选范围存在未知、冷却跳过或缓存复用。0 确认失效不表示正常；请先查看原因及历史检查日期。']
        : ['> 结论仅适用于本次已确认的视频；少量复核不能证明全目录正常。']),
    ...(confirmed < audit.entries.length ? ['> 全目录巡检未完成；未检查和未知项不能视为可用。'] : []),
    ...(audit.state.cooldownUntil ? [`> 冷却记录：${audit.state.cooldownUntil}；到期后仍需人工按需发起，不自动重试。`] : []),
    '',
    '确认失效是接口明确返回不存在/不可见；人工核实后再决定处理，不自动删除菜谱或链接。未知和未检查不会覆盖历史可信记录。',
    '',
    ...(rows.length
      ? [
          '| 视频 | 菜谱 id / 名称 | 本次状态 | 原因 | 最后可信状态 / 检查日期 |',
          '| --- | --- | --- | --- | --- |',
          ...rows.map((entry) => {
            const previous = audit.state.trusted[entry.id]
            return `| ${entry.id} | ${cell(entry.uses.join('、'))} | ${labels[entry.status]} | ${cell(entry.detail)} | ${previous ? `${labels[previous.status]} / ${previous.checkedAt}` : '无可信记录'} |`
          }),
        ]
      : ['本次无逐视频可信记录；全部视频均未检查。']),
    '',
  ].join('\n')
}
