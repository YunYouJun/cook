/**
 * 校验 app/data/recipe.csv：食材/厨具词典、取值、视频号、重复
 *
 * pnpm validate          # 有错误时退出码为 1
 * pnpm validate --json   # 输出 JSON，供 AI / 脚本消费
 */
import fs from 'node:fs'
import process from 'node:process'
import { meat, staple, tools, vegetable } from '../app/data/food'
import { recipeCsvFile } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { validateRecipes } from '../packages/cook/src/utils/validate'

const csvPath = 'app/data/recipe.csv'
const recipes = parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8'))
const { issues, stats } = validateRecipes(recipes, {
  stuff: new Set([...vegetable, ...meat, ...staple].map(item => item.name)),
  tools: new Set(tools.map(item => item.name)),
})
const errors = issues.filter(i => i.level === 'error')
const warnings = issues.filter(i => i.level === 'warning')

if (process.argv.includes('--json')) {
  console.log(JSON.stringify({ stats, issues }, null, 2))
}
else {
  const inCI = !!process.env.GITHUB_ACTIONS
  for (const issue of issues) {
    const text = `第 ${issue.line} 行「${issue.name}」：${issue.message}`
    console.log(inCI ? `::${issue.level} file=${csvPath},line=${issue.line}::${text}` : `${issue.level === 'error' ? '✗' : '!'} ${text}`)
  }

  const percent = (n: number) => `${n}（${Math.round(n / stats.total * 100)}%）`
  const summary = [
    `### 菜谱数据校验`,
    '',
    `| 项目 | 数量 |`,
    `| --- | --- |`,
    `| 菜谱总数 | ${stats.total} |`,
    `| 错误 | ${errors.length} |`,
    `| 警告 | ${warnings.length} |`,
    `| 缺难度 | ${percent(stats.missingDifficulty)} |`,
    `| 缺做法 | ${percent(stats.missingMethods)} |`,
    `| 缺视频 | ${percent(stats.missingVideo)} |`,
    '',
  ].join('\n')
  console.log(`\n${summary}`)
  if (process.env.GITHUB_STEP_SUMMARY)
    fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, summary)
}

process.exitCode = errors.length ? 1 : 0
