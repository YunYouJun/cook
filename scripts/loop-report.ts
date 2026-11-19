/**
 * 汇总 Cook 的可衡量指标（数据校验、跨端契约漂移、离线视频覆盖、搜索评测），
 * 输出下一轮 Looping 行动建议，供维护者或 Agent（recipe-data-review / recipe-sourcing）直接消费。
 *
 * pnpm audit:loop             # 打印 Markdown 体检报告
 * pnpm audit:loop --json      # 输出结构化 JSON
 * pnpm audit:loop --out PATH  # 写入报告文件
 * pnpm audit:loop --check     # 存在校验错误或 fixtures 漂移时返回非零退出码
 */
import type { EvalCase } from './lib/feedback'
import type { AuditState } from './lib/video-check'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { parseArgs } from 'node:util'
import { meat, staple, tools, vegetable } from '../apps/web/app/data/food'
import { defaultPantry } from '../apps/web/app/data/pantry'
import {
  incompatibleFoodsCsvFile,
  recipeCsvFile,
  rootDir,
} from '../packages/cook/src/utils/config'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { applyRecipeScores } from '../packages/cook/src/utils/score'
import { readVideoStats } from '../packages/cook/src/utils/video-stats'
import { buildLoopReport, renderLoopReportMarkdown } from './lib/loop-report'

const { values } = parseArgs({
  options: {
    json: { type: 'boolean', default: false },
    check: { type: 'boolean', default: false },
    out: { type: 'string' },
  },
})

const recipes = parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8'))
const scoredRecipes = applyRecipeScores(recipes, readVideoStats())
const incompatibleRules = parseIncompatibleFoodsCsv(fs.readFileSync(incompatibleFoodsCsvFile, 'utf-8'))

const evalFile = path.join(rootDir, 'test/fixtures/search-eval.json')
const evalCases = fs.existsSync(evalFile)
  ? JSON.parse(fs.readFileSync(evalFile, 'utf-8')) as EvalCase[]
  : []

const fixturesDir = path.join(rootDir, 'packages/data/fixtures')
const committedFixtures: Record<string, unknown> = {}
for (const name of ['recommend.json', 'search.json', 'incompatible.json']) {
  const file = path.join(fixturesDir, name)
  if (fs.existsSync(file))
    committedFixtures[name] = JSON.parse(fs.readFileSync(file, 'utf-8'))
}

const videoStateFile = path.join(rootDir, '.cook/video-checks/state.json')
const videoAuditState: AuditState = fs.existsSync(videoStateFile)
  ? JSON.parse(fs.readFileSync(videoStateFile, 'utf-8')) as AuditState
  : { trusted: {} }

const report = await buildLoopReport({
  recipes,
  scoredRecipes,
  incompatibleRules,
  dict: {
    stuff: new Set([...vegetable, ...meat, ...staple].map(item => item.name)),
    tools: new Set(tools.map(item => item.name)),
  },
  defaultPantry,
  evalCases,
  committedFixtures,
  videoAuditState,
})

const output = values.json
  ? `${JSON.stringify(report, null, 2)}\n`
  : renderLoopReportMarkdown(report)

if (values.out) {
  const outPath = path.resolve(values.out)
  fs.mkdirSync(path.dirname(outPath), { recursive: true })
  fs.writeFileSync(outPath, output)
}

process.stdout.write(output)

if (values.check && (report.validation.errors > 0 || !report.fixtures.inSync))
  process.exitCode = 1
