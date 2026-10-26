/** 默认离线；联网仅允许本机显式选择最多 20 个视频。见 docs/dev/data.md。 */
import type { AuditState } from './lib/video-check'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { parseArgs } from 'node:util'
import { recipeCsvFile, rootDir } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { auditVideos, changedVideoIds, renderVideoReport, selectVideoIds } from './lib/video-check'

const { values } = parseArgs({ options: {
  online: { type: 'boolean', default: false },
  ids: { type: 'string' },
  changed: { type: 'string' },
  out: { type: 'string' },
} })

if (values.online && (process.env.CI || process.env.GITHUB_ACTIONS))
  throw new Error('CI 只允许离线静态校验；联网复核请在本机明确选择少量视频。')
if (values.ids !== undefined && values.changed !== undefined)
  throw new Error('--ids 和 --changed 只能选一个。')

function readRecipes(csv: string) {
  const recipes = parseRecipeCsv(csv)
  if (!recipes.length)
    throw new Error('菜谱目录为空或表头不正确，请先运行 pnpm validate。')
  return recipes.map((recipe) => {
    if (recipe.id === undefined)
      throw new Error('菜谱缺少稳定 id，请先运行 pnpm validate。')
    return { ...recipe, id: recipe.id }
  })
}
const recipes = readRecipes(fs.readFileSync(recipeCsvFile, 'utf-8'))
let requested = values.ids?.split(',').map(id => id.trim()).filter(Boolean)
if (values.changed !== undefined) {
  if (!values.changed || values.changed.startsWith('-'))
    throw new Error('--changed 需要有效的 Git 基准 ref。')
  const base = execFileSync('git', ['show', `${values.changed}:packages/data/recipe.csv`], { cwd: rootDir, encoding: 'utf-8' })
  requested = changedVideoIds(recipes, readRecipes(base))
}
const ids = selectVideoIds(recipes, requested, values.online)
const stateFile = path.join(rootDir, '.cook/video-checks/state.json')
const lockFile = `${stateFile}.lock`
const out = path.resolve(values.out ?? path.join(rootDir, '.cook/video-checks/latest.md'))
if ([stateFile, lockFile, `${stateFile}.tmp`].includes(out))
  throw new Error('--out 不能覆盖巡检状态或锁文件。')
let locked = false
try {
  if (values.online) {
    fs.mkdirSync(path.dirname(stateFile), { recursive: true })
    fs.writeFileSync(lockFile, `${process.pid}\n`, { flag: 'wx' })
    locked = true
  }
  // 损坏的状态直接报错，不能因读失败而丢掉冷却期或可信历史。
  const state: AuditState = fs.existsSync(stateFile)
    ? JSON.parse(fs.readFileSync(stateFile, 'utf-8'))
    : { trusted: {} }
  const result = await auditVideos(recipes, { online: values.online, ids, state })
  // 先持久化冷却及可信状态；即使报告路径无效，也不能丢掉本次冷却。
  if (values.online) {
    fs.writeFileSync(`${stateFile}.tmp`, `${JSON.stringify(result.state, null, 2)}\n`)
    fs.renameSync(`${stateFile}.tmp`, stateFile)
  }
  const report = renderVideoReport(result)
  fs.mkdirSync(path.dirname(out), { recursive: true })
  fs.writeFileSync(out, report)
  console.log(report)
  if (values.online && result.entries.some(entry => ids.includes(entry.id) && (entry.status === 'unknown' || (entry.status === 'unchecked' && !entry.cached))))
    process.exitCode = 2
}
finally {
  if (locked)
    fs.unlinkSync(lockFile)
}
