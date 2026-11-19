import type { IncompatibleRule, RecipeItem } from '../packages/cook/src/types/index'
import { describe, expect, it } from 'vitest'
import { buildFixtures } from '../scripts/lib/fixtures'
import { buildLoopReport, renderLoopReportMarkdown } from '../scripts/lib/loop-report'

const recipes: RecipeItem[] = [
  {
    id: 1,
    name: '番茄炒蛋',
    stuff: ['番茄', '鸡蛋'],
    bv: 'BV1rf4y1872R',
    difficulty: '简单',
    tags: ['家常菜'],
    methods: ['炒'],
    tools: ['一口大锅'],
  },
  {
    id: 2,
    name: '清炒土豆丝',
    stuff: ['土豆'],
    bv: 'BV1xx411c7mD',
    difficulty: '',
    tags: [],
    methods: [],
    tools: ['一口大锅'],
  },
]

const incompatibleRules: IncompatibleRule[] = [
  { foodA: '牛奶', foodB: '韭菜', reason: '测试' },
]

const dict = {
  stuff: new Set(['番茄', '鸡蛋', '土豆', '牛肉']),
  tools: new Set(['一口大锅']),
}

describe('buildLoopReport', () => {
  it('summarizes validation, fixtures sync, video status, and search gaps', async () => {
    const committedFixtures = JSON.parse(JSON.stringify(buildFixtures(recipes, incompatibleRules)))
    const report = await buildLoopReport({
      recipes,
      scoredRecipes: recipes,
      incompatibleRules,
      dict,
      defaultPantry: ['油', '盐'],
      evalCases: [
        { stuff: ['番茄', '鸡蛋'], opened: [{ id: 1, count: 3 }] },
        { stuff: ['牛肉'], empty: 4 },
      ],
      committedFixtures,
      videoAuditState: {
        trusted: {
          BV1xx411c7mD: { status: 'dead', checkedAt: '2026-11-19T00:00:00.000Z', detail: '-404' },
        },
      },
      now: new Date('2026-11-20T00:00:00.000Z'),
    })

    expect(report.validation.total).toBe(2)
    expect(report.validation.errors).toBe(0)
    expect(report.fixtures.inSync).toBe(true)
    expect(report.videos.dead).toBe(1)
    expect(report.searchEval.empty.stillEmpty).toBe(4)
    expect(report.searchEval.topEmptyQueries).toEqual([{ stuff: ['牛肉'], count: 4 }])
    expect(report.actions.some(a => a.area === 'videos' && a.priority === 'high')).toBe(true)
    expect(report.actions.some(a => a.area === 'search' && a.skill === 'recipe-sourcing')).toBe(true)

    const markdown = renderLoopReportMarkdown(report)
    expect(markdown).toContain('Cook 智能进化闭环体检报告')
    expect(markdown).toContain('recipe-sourcing')
  })

  it('detects fixture drift as a high-priority cross-platform action', async () => {
    const report = await buildLoopReport({
      recipes,
      scoredRecipes: recipes,
      incompatibleRules,
      dict,
      defaultPantry: ['油', '盐'],
      evalCases: [],
      committedFixtures: { 'recommend.json': { stale: true } },
      videoAuditState: { trusted: {} },
      now: new Date('2026-11-20T00:00:00.000Z'),
    })

    expect(report.fixtures.inSync).toBe(false)
    expect(report.fixtures.drifted).toContain('recommend.json')
    expect(report.actions.some(a => a.area === 'fixtures' && a.skill === 'cross-platform-sync')).toBe(true)
  })
})
