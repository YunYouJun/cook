import type { IncompatibleRule, RecipeItem } from '../../packages/cook/src/types/index'
import type { ValidateDict } from '../../packages/cook/src/utils/validate'
import type { EvalCase } from './feedback'
import type { AuditState } from './video-check'
import { validateRecipes } from '../../packages/cook/src/utils/validate'
import { evaluate } from './feedback'
import { buildFixtures } from './fixtures'
import { auditVideos } from './video-check'

export interface LoopAction {
  priority: 'high' | 'medium' | 'low'
  area: 'data' | 'fixtures' | 'videos' | 'search'
  message: string
  command?: string
  skill?: string
}

export interface LoopReport {
  generatedAt: string
  validation: {
    total: number
    errors: number
    warnings: number
    missingDifficulty: number
    missingDifficultyPct: number
    missingMethods: number
    missingMethodsPct: number
    missingSource: number
    missingSourcePct: number
  }
  fixtures: {
    inSync: boolean
    drifted: string[]
  }
  videos: {
    totalVideos: number
    ok: number
    dead: number
    unknown: number
    unchecked: number
    textOnlyRecipes: number
  }
  searchEval: {
    cases: number
    opened: {
      total: number
      hitAt10: number
      mrr: number
      missing: number
    }
    empty: {
      total: number
      stillEmpty: number
    }
    topEmptyQueries: { stuff: string[], tool?: string, count: number }[]
    worstOpened: { stuff: string[], id: number, rank: number, count: number }[]
  }
  actions: LoopAction[]
}

export interface BuildLoopReportInput {
  recipes: RecipeItem[]
  scoredRecipes: RecipeItem[]
  incompatibleRules: IncompatibleRule[]
  dict: ValidateDict
  defaultPantry: string[]
  evalCases: EvalCase[]
  committedFixtures: Record<string, unknown>
  videoAuditState: AuditState
  now?: Date
}

function pct(part: number, total: number): number {
  if (!total)
    return 0
  return Math.round((part / total) * 100)
}

export async function buildLoopReport(input: BuildLoopReportInput): Promise<LoopReport> {
  const now = input.now ?? new Date()
  const { issues, stats } = validateRecipes(input.recipes, input.dict)
  const errors = issues.filter(i => i.level === 'error').length
  const warnings = issues.filter(i => i.level === 'warning').length

  const expectedFixtures = buildFixtures(input.scoredRecipes, input.incompatibleRules)
  const drifted: string[] = []
  for (const [name, content] of Object.entries(expectedFixtures)) {
    const expectedNormalized = JSON.stringify(content)
    const actualNormalized = JSON.stringify(input.committedFixtures[name] ?? null)
    if (expectedNormalized !== actualNormalized)
      drifted.push(name)
  }

  const videoInputs = input.recipes
    .filter((r): r is RecipeItem & { id: number } => r.id !== undefined)
    .map(r => ({ id: r.id, name: r.name, bv: r.bv }))
  const videoAudit = await auditVideos(videoInputs, {
    online: false,
    ids: [],
    state: input.videoAuditState,
    now: () => now.getTime(),
  })

  const videoCounts = { ok: 0, dead: 0, unknown: 0, unchecked: 0 }
  for (const entry of videoAudit.entries) {
    const effectiveStatus = entry.status === 'unchecked'
      ? (videoAudit.state.trusted[entry.id]?.status ?? 'unchecked')
      : entry.status
    videoCounts[effectiveStatus] += 1
  }
  const textOnlyRecipes = input.recipes.filter(r => !r.bv && Boolean(r.link)).length

  const searchResult = evaluate(input.recipes, input.evalCases, input.defaultPantry)
  const topEmptyQueries = input.evalCases
    .filter(c => (c.empty ?? 0) > 0)
    .sort((a, b) => (b.empty ?? 0) - (a.empty ?? 0))
    .slice(0, 5)
    .map(c => ({
      stuff: c.stuff,
      ...(c.tool ? { tool: c.tool } : {}),
      count: c.empty ?? 0,
    }))

  const actions: LoopAction[] = []
  if (errors > 0) {
    actions.push({
      priority: 'high',
      area: 'data',
      message: `存在 ${errors} 条菜谱数据校验错误，需先修复后再合并。`,
      command: 'pnpm validate',
      skill: 'recipe-data-review',
    })
  }
  if (drifted.length > 0) {
    actions.push({
      priority: 'high',
      area: 'fixtures',
      message: `共享契约用例已漂移（${drifted.join('、')}），需重新生成并同步 Web/iOS/Android 核心实现。`,
      command: 'pnpm fixtures',
      skill: 'cross-platform-sync',
    })
  }
  if (videoCounts.dead > 0) {
    actions.push({
      priority: 'high',
      area: 'videos',
      message: `有 ${videoCounts.dead} 个已确认失效的 B 站视频，建议检索替代视频或补全图文来源。`,
      command: 'pnpm check:videos',
      skill: 'recipe-data-review',
    })
  }
  if (searchResult.empty.stillEmpty > 0) {
    actions.push({
      priority: 'medium',
      area: 'search',
      message: `真实筛选信号中仍有 ${searchResult.empty.stillEmpty} 次无结果组合，优先检查别名映射或定向补充菜谱。`,
      command: 'pnpm eval:search',
      skill: 'recipe-sourcing',
    })
  }
  if (searchResult.cases === 0) {
    actions.push({
      priority: 'low',
      area: 'search',
      message: '尚未加载真实搜索评测样例，配置 .env 后可拉取线上反馈信号形成搜索优化闭环。',
      command: 'pnpm feedback:pull',
      skill: 'recipe-data-review',
    })
  }
  if (stats.missingDifficulty > 0 || stats.missingMethods > 0) {
    actions.push({
      priority: 'low',
      area: 'data',
      message: `还有 ${stats.missingDifficulty} 道菜缺难度、${stats.missingMethods} 道菜缺做法，可分批核实补全。`,
      skill: 'recipe-data-review',
    })
  }

  return {
    generatedAt: now.toISOString(),
    validation: {
      total: stats.total,
      errors,
      warnings,
      missingDifficulty: stats.missingDifficulty,
      missingDifficultyPct: pct(stats.missingDifficulty, stats.total),
      missingMethods: stats.missingMethods,
      missingMethodsPct: pct(stats.missingMethods, stats.total),
      missingSource: stats.missingVideo,
      missingSourcePct: pct(stats.missingVideo, stats.total),
    },
    fixtures: {
      inSync: drifted.length === 0,
      drifted,
    },
    videos: {
      totalVideos: videoAudit.entries.length,
      ...videoCounts,
      textOnlyRecipes,
    },
    searchEval: {
      cases: searchResult.cases,
      opened: searchResult.opened,
      empty: searchResult.empty,
      topEmptyQueries,
      worstOpened: searchResult.worst.slice(0, 5),
    },
    actions,
  }
}

export function renderLoopReportMarkdown(report: LoopReport): string {
  const { validation, fixtures, videos, searchEval, actions } = report
  const lines: string[] = [
    '## Cook 智能进化闭环体检报告',
    '',
    `生成时间：${report.generatedAt}`,
    '',
    '### 1. 核心可衡量指标',
    '',
    '| 维度 | 指标 | 当前状态 |',
    '| --- | --- | --- |',
    `| 菜谱数据 | 总数 / 错误 / 警告 | ${validation.total} / ${validation.errors} / ${validation.warnings} |`,
    `| 数据完整度 | 缺难度 / 缺做法 / 缺来源 | ${validation.missingDifficulty} (${validation.missingDifficultyPct}%) / ${validation.missingMethods} (${validation.missingMethodsPct}%) / ${validation.missingSource} (${validation.missingSourcePct}%) |`,
    `| 跨端共享契约 | Fixtures 一致性 | ${fixtures.inSync ? '一致' : `漂移：${fixtures.drifted.join('、')}`} |`,
    `| 来源覆盖 | 视频数（正常 / 失效 / 未核验）+ 纯图文 | ${videos.totalVideos}（${videos.ok} / ${videos.dead} / ${videos.unchecked}）+ ${videos.textOnlyRecipes} |`,
    `| 搜索评测 | 样例数 / hitAt10 / MRR / 仍无结果 | ${searchEval.cases} / ${searchEval.opened.hitAt10} / ${searchEval.opened.mrr} / ${searchEval.empty.stillEmpty} |`,
    '',
  ]

  if (searchEval.topEmptyQueries.length > 0) {
    lines.push('### 2. 高频无结果搜索（补菜 / 别名线索）', '')
    for (const q of searchEval.topEmptyQueries) {
      const toolNote = q.tool ? `（厨具：${q.tool}）` : ''
      lines.push(`- ${q.stuff.join('、')}${toolNote}：${q.count} 次`)
    }
    lines.push('')
  }

  lines.push('### 下一轮 Looping 行动建议', '')
  if (actions.length === 0) {
    lines.push('- 当前所有核心指标健康，无需紧急干预。')
  }
  else {
    for (const action of actions) {
      const tag = action.priority === 'high' ? '[高]' : action.priority === 'medium' ? '[中]' : '[低]'
      const extras = [
        action.command ? `命令：\`${action.command}\`` : '',
        action.skill ? `Skill：\`${action.skill}\`` : '',
      ].filter(Boolean).join(' · ')
      lines.push(`- ${tag} ${action.message}${extras ? `（${extras}）` : ''}`)
    }
  }
  lines.push('')

  return lines.join('\n')
}
