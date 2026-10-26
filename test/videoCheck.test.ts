// @vitest-environment node
import type { AuditState } from '../scripts/lib/video-check'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { auditVideos, changedVideoIds, checkVideo, DELAY_MS, renderVideoReport, selectVideoIds } from '../scripts/lib/video-check'

const now = () => Date.parse('2026-10-26T08:00:00Z')
const recipes = ['av1', 'av2', 'av3', 'av4'].map((bv, id) => ({ id, name: `菜${id}`, bv }))
const success = (aid = 1) => new Response(JSON.stringify({ code: 0, data: { aid } }))
const old: AuditState = { trusted: { av1: { status: 'ok', checkedAt: '2026-10-01T00:00:00Z', detail: '0' } } }
const wait = vi.fn(async () => {})
const run = (request: typeof fetch, ids = ['av1', 'av2', 'av3', 'av4'], state?: AuditState) => auditVideos(recipes, { online: true, ids, request, wait, now, state })

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  wait.mockClear()
})

describe('offline and explicit selection', () => {
  it('never requests in offline mode and does not invent availability or a trusted date', async () => {
    const request = vi.fn<typeof fetch>()
    const audit = await auditVideos(recipes, { online: false, ids: [], request, now })
    expect(request).not.toHaveBeenCalled()
    expect(audit.entries.every(entry => entry.status === 'unchecked')).toBe(true)
    expect(renderVideoReport(audit)).toContain('确认失效 0，未知 0，未检查 4')
    expect(renderVideoReport(audit)).toContain('无可信记录')
    expect(renderVideoReport(audit)).toContain('0/4 = 0.0%')
    expect(renderVideoReport(audit)).not.toContain('没有发现失效视频')
  })

  it('requires an explicit bounded selection and validates every catalog id', () => {
    expect(() => selectVideoIds(recipes, undefined, true)).toThrow('显式指定')
    expect(() => selectVideoIds(recipes, ['av999'], true)).toThrow('当前 recipe.csv')
    expect(() => selectVideoIds([{ id: 0, name: 'invalid', bv: 'https://bad' }], [], false)).toThrow('格式错误')
    const many = Array.from({ length: 21 }, (_, id) => ({ id, name: '', bv: `av${id}` }))
    expect(() => selectVideoIds(many, many.map(recipe => recipe.bv), true)).toThrow('最多复核 20')
  })

  it('selects only added or changed videos by stable recipe id', () => {
    expect(changedVideoIds([{ ...recipes[0]!, name: '重命名' }, { ...recipes[1]!, bv: 'av4' }, recipes[2]!], recipes.slice(0, 2))).toEqual(['av4', 'av3'])
  })

  it('does not request empty input or empty changed scope', async () => {
    const request = vi.fn<typeof fetch>()
    const empty = await auditVideos([], { online: true, ids: [], request, now })
    expect(renderVideoReport(empty)).toContain('不适用（空范围）')
    expect(renderVideoReport(empty)).not.toMatch(/NaN|Infinity/)
    await run(request, [])
    expect(request).not.toHaveBeenCalled()
  })
})

describe('serial requests and trustworthy reporting', () => {
  it('deduplicates requests and counts videos separately from recipe uses', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(success())
    const audit = await auditVideos([recipes[0]!, { ...recipes[0]!, id: 99 }], { online: true, ids: ['av1', 'av1'], request, wait, now })
    expect(request).toHaveBeenCalledTimes(1)
    expect(audit.entries[0]?.uses).toHaveLength(2)
    expect(renderVideoReport(audit)).toContain('目录去重视频 1 个')
    expect(renderVideoReport(audit)).toContain('1/1 = 100.0%')
    expect(audit.state.trusted.av1?.checkedAt).toBe('2026-10-26T08:00:00.000Z')
  })

  it('waits five seconds after each completed request and keeps dead distinct from unknown', async () => {
    const order: string[] = []
    const request = vi.fn<typeof fetch>().mockImplementation(async () => {
      order.push('request')
      return order.length === 1 ? success() : new Response('{"code":-404}')
    })
    const audit = await auditVideos(recipes, { online: true, ids: ['av1', 'av2'], request, now, wait: async (ms) => {
      order.push(`wait ${ms}`)
    } })
    expect(order).toEqual(['request', `wait ${DELAY_MS}`, 'request'])
    expect(audit.entries.map(entry => entry.status)).toEqual(['ok', 'dead', 'unchecked', 'unchecked'])
    const report = renderVideoReport(audit)
    expect(report).toContain('可信覆盖率（本次确定结果 / 目录）：2/4 = 50.0%')
    expect(report).toContain('所选范围可信覆盖率：2/2 = 100.0%')
    expect(report).toContain('确认失效 / 2026-10-26')
    expect(report).toContain('全目录巡检未完成')
  })

  it.each([412, 429])('stops after the first HTTP %s, never marks dead, and persists cooldown', async (status) => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(new Response('', { status }))
    const audit = await run(request, undefined, old)
    expect(request).toHaveBeenCalledTimes(1)
    expect(wait).not.toHaveBeenCalled()
    expect(audit.entries.map(entry => entry.status)).toEqual(['unknown', 'unchecked', 'unchecked', 'unchecked'])
    expect(audit.state.trusted).toEqual(old.trusted)
    expect(audit.state.cooldownUntil).toBe('2026-10-27T08:00:00.000Z')
    const report = renderVideoReport(audit)
    expect(report).toContain('巡检受阻')
    expect(report).toContain('0 确认失效不表示正常')
    expect(report).toContain('可用 / 2026-10-01')
    const nextRequest = vi.fn<typeof fetch>()
    const next = await run(nextRequest, undefined, audit.state)
    expect(nextRequest).not.toHaveBeenCalled()
    expect(next.state).toEqual(audit.state)
  })

  it.each([-412, -429])('also stops on API code %s', async (code) => {
    const request = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify({ code })))
    const audit = await run(request)
    expect(request).toHaveBeenCalledTimes(1)
    expect(audit.entries[0]?.status).toBe('unknown')
    expect(audit.state.cooldownUntil).toBeDefined()
  })

  it('keeps partial successes, preserves unknown historical results, and leaves remaining items unchecked', async () => {
    const request = vi.fn<typeof fetch>().mockResolvedValueOnce(success(2)).mockResolvedValueOnce(new Response('', { status: 412 }))
    const audit = await run(request, ['av2', 'av1', 'av3'], old)
    expect(request).toHaveBeenCalledTimes(2)
    expect(wait).toHaveBeenCalledExactlyOnceWith(5000)
    expect(audit.state.trusted.av1).toEqual(old.trusted.av1)
    expect(audit.state.trusted.av2?.status).toBe('ok')
    expect(renderVideoReport(audit)).toContain('可用 1，确认失效 0，未知 1，未检查 2')
    expect(renderVideoReport(audit)).toContain('1/3 = 33.3%')
  })

  it('timeouts remain unknown, use a 15 second signal, and stop after three consecutive failures', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    const request = vi.fn<typeof fetch>().mockRejectedValue(new DOMException('timed out', 'TimeoutError'))
    const audit = await run(request, undefined, old)
    expect(request).toHaveBeenCalledTimes(3)
    expect(timeout).toHaveBeenCalledWith(15000)
    expect(audit.entries.map(entry => entry.status)).toEqual(['unknown', 'unknown', 'unknown', 'unchecked'])
    expect(audit.state.trusted).toEqual(old.trusted)
    expect(audit.state.cooldownUntil).toBeDefined()
  })

  it('reuses recent confirmed records without presenting them as a fresh check', async () => {
    const state: AuditState = { trusted: { av1: { ...old.trusted.av1!, checkedAt: '2026-10-25T00:00:00Z' } } }
    const request = vi.fn<typeof fetch>()
    const audit = await run(request, ['av1'], state)
    expect(request).not.toHaveBeenCalled()
    expect(audit.state).toEqual(state)
    expect(audit.entries[0]?.status).toBe('unchecked')
    expect(renderVideoReport(audit)).toContain('0/4 = 0.0%')
    expect(renderVideoReport(audit)).toContain('可用 / 2026-10-25')
  })

  it('rejects corrupt state without requesting anything', async () => {
    const request = vi.fn<typeof fetch>()
    await expect(run(request, [], { trusted: {}, cooldownUntil: 'invalid' })).rejects.toThrow('格式错误')
    expect(request).not.toHaveBeenCalled()
  })

  it('handles malformed responses and keeps HTTP 404 unknown', async () => {
    for (const response of [new Response('{'), new Response('{"code":0}'), success(999), new Response('', { status: 404 })]) {
      const result = await checkVideo('av1', vi.fn<typeof fetch>().mockResolvedValue(response))
      expect(result.status).toBe('unknown')
    }
  })

  it('escapes external message text in the markdown table', async () => {
    const audit = await run(vi.fn<typeof fetch>().mockResolvedValue(new Response('{"code":-404,"message":"<tag>|bad"}')), ['av1'])
    expect(renderVideoReport(audit)).toContain('&lt;tag&gt;&#124;bad')
  })
})

describe('cLI and workflow safety', () => {
  const root = path.resolve(import.meta.dirname, '..')
  const cli = (args: string[]) => execFileSync(process.execPath, ['--import', 'tsx', 'scripts/check-video-links.ts', ...args], { cwd: root, env: { ...process.env, CI: 'true' }, encoding: 'utf8', stdio: 'pipe' })

  it('runs the default CLI offline and preserves scoring data', () => {
    const out = 'artifacts/video-cli-test.md'
    const before = fs.readFileSync(path.join(root, 'packages/data/video-stats.json'), 'utf8')
    const report = cli(['--out', out])
    expect(report).toContain('实际请求 0 个')
    expect(report).toMatch(/未知 0，未检查 [1-9]\d*/)
    expect(fs.readFileSync(path.join(root, 'packages/data/video-stats.json'), 'utf8')).toBe(before)
    expect(fs.readFileSync(path.join(root, out), 'utf8')).toContain('离线静态校验')
  })

  it('rejects online mode on CI before any requests', () => {
    expect(() => cli(['--online', '--ids', 'av1'])).toThrow('CI 只允许离线静态校验')
    expect(() => cli(['--stats'])).toThrow()
  })

  it('has no scheduled/network audit or issue write permission in the workflow', () => {
    const workflow = fs.readFileSync(path.join(root, '.github/workflows/check-videos.yml'), 'utf8')
    expect(workflow).not.toMatch(/schedule:|cron:|issues:|gh issue|--online|--stats/)
    expect(workflow).toContain('pnpm run check:videos --out')
    expect(workflow).toContain('pnpm run validate')
    expect(workflow).toContain('test/videoCheck.test.ts')
  })
})
