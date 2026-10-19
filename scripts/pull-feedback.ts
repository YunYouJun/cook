import type { AppFeedbackRow, FeedbackRow, SignalRow } from './lib/feedback'
/**
 * 拉取 cook-feedback 云函数里的用户反馈和筛选信号，汇总给 recipe-data-review skill 使用
 *
 * 需要在 .env 中配置：
 *   COOK_FEEDBACK_URL    cook-feedback 云函数的 HTTP 访问地址
 *   COOK_FEEDBACK_TOKEN  与云函数环境变量 COOK_FEEDBACK_EXPORT_TOKEN 相同
 *
 * pnpm feedback:pull
 *   → .cook/feedback/summary.md         给人和 AI 看的汇总
 *   → .cook/feedback/export.json        原始数据（不提交）
 *   → test/fixtures/search-eval.json    筛选评测样例（匿名聚合，可以提交）
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { recipeCsvFile, rootDir } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { buildEvalCases, countByPlatform, formatPlatforms, platformOf, summarizeReports, summarizeSignals } from './lib/feedback'

try {
  process.loadEnvFile(path.join(rootDir, '.env'))
}
catch {}

const url = process.env.COOK_FEEDBACK_URL
const token = process.env.COOK_FEEDBACK_TOKEN
if (!url || !token) {
  console.error('请先在 .env 中配置 COOK_FEEDBACK_URL 和 COOK_FEEDBACK_TOKEN，见 docs/dev/data.md')
  process.exit(1)
}

const res = await fetch(url, { headers: { 'x-cook-token': token }, signal: AbortSignal.timeout(30_000) })
const data = await res.json() as { ok?: boolean, code?: string, feedback?: FeedbackRow[], signals?: SignalRow[], appFeedback?: AppFeedbackRow[] }
if (!res.ok || !data.ok) {
  console.error(`拉取失败：${res.status} ${data.code ?? ''}`)
  process.exit(1)
}

const outDir = path.join(rootDir, '.cook/feedback')
fs.mkdirSync(outDir, { recursive: true })
fs.writeFileSync(path.join(outDir, 'export.json'), JSON.stringify(data, null, 2))

const recipes = new Map(parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8')).map(r => [r.id, r]))
const reports = summarizeReports(data.feedback ?? [])
const signals = data.signals ?? []
const empties = signals.filter(s => s.type === 'empty').sort((a, b) => b.count - a.count).slice(0, 30)
const signalStats = summarizeSignals(signals)
const cases = buildEvalCases(signals)
fs.writeFileSync(path.join(rootDir, 'test/fixtures/search-eval.json'), `${JSON.stringify(cases, null, 2)}\n`)

const kindLabel: Record<string, string> = { video: '视频打不开', stuff: '食材或厨具不对', info: '难度或做法不对', other: '其他' }
const categoryLabel: Record<string, string> = { bug: '用着有问题', idea: '功能建议', recipe: '想要的菜没有', other: '其他' }
const appFeedback = [...data.appFeedback ?? []].sort((a, b) => b.day.localeCompare(a.day))
const cell = (text: string) => text.replace(/\|/g, '/').replace(/\s+/g, ' ')
const lines = [
  `# 用户反馈汇总（${new Date().toISOString().slice(0, 10)}）`,
  '',
  `菜谱反馈 ${data.feedback?.length ?? 0} 条（${formatPlatforms(countByPlatform(data.feedback ?? []))}），涉及 ${reports.length} 道菜；意见反馈 ${appFeedback.length} 条（${formatPlatforms(countByPlatform(appFeedback))}）；筛选信号 ${signals.length} 组。`,
  '',
  '没有记录来源的旧数据都算作 web。',
  '',
  '## 菜谱反馈（按分数排序，登录用户的反馈算 2 分）',
  '',
  '| id | 菜名 | 分数 | 人数 | 来源 | 问题 | 备注 |',
  '| --- | --- | --- | --- | --- | --- | --- |',
  ...reports.map((r) => {
    const current = recipes.get(r.recipeId)
    const name = current ? current.name : `${r.recipeName}（已不在数据中）`
    const kinds = Object.entries(r.kinds).map(([k, n]) => `${kindLabel[k] ?? k}×${n}`).join('、')
    return `| ${r.recipeId} | ${name} | ${r.score} | ${r.reporters} | ${formatPlatforms(r.platforms)} | ${kinds} | ${r.notes.slice(0, 3).join('；').replace(/\|/g, '/')} |`
  }),
  '',
  '## 意见反馈（新的在前）',
  '',
  '联系方式只在 `.cook/feedback/export.json` 里，不要抄进 PR 或 issue。',
  '',
  '| 日期 | 来源 | 类型 | 内容 | 留了联系方式 |',
  '| --- | --- | --- | --- | --- |',
  ...appFeedback.map(f => `| ${f.day} | ${f.platform}${f.version ? ` ${f.version}` : ''} | ${categoryLabel[f.category] ?? f.category} | ${cell(f.note)} | ${f.contact ? '是' : '-'} |`),
  '',
  '## 筛选信号按端统计',
  '',
  '两端界面不同，「没有结果」和点开位置分开看。',
  '',
  '| 端 | 没有结果（次） | 点开结果（次） | 点开的平均位置 |',
  '| --- | --- | --- | --- |',
  ...signalStats.map(s => `| ${s.platform} | ${s.empty} | ${s.opened} | ${s.meanRank ?? '-'} |`),
  '',
  '## 没有结果的食材组合（缺哪些菜）',
  '',
  '| 食材 | 厨具 | 模式 | 端 | 次数 |',
  '| --- | --- | --- | --- | --- |',
  ...empties.map(s => `| ${s.stuff.join('、')} | ${s.tool || '-'} | ${s.mode} | ${platformOf(s)} | ${s.count} |`),
  '',
  '筛选排序的评测见 `pnpm eval:search`。',
  '',
]
fs.writeFileSync(path.join(outDir, 'summary.md'), lines.join('\n'))
console.log(`已写入 .cook/feedback/summary.md，评测样例 ${cases.length} 组`)
