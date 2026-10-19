import type { EvalCase } from './lib/feedback'
/**
 * 用 test/fixtures/search-eval.json 里的真实筛选信号评估当前的推荐逻辑
 *
 * pnpm eval:search          # 打印指标
 * pnpm eval:search --json   # 输出 JSON，供 AI 在修改前后对比
 *
 * 指标：
 * - 点开的菜排在前 10 的比例（hitAt10）和平均倒数排名（mrr），越高越好
 * - 曾经没有结果、现在仍然没有结果的次数（stillEmpty），越低越好
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { defaultPantry } from '../apps/web/app/data/pantry'
import { recipeCsvFile, rootDir } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { evaluate } from './lib/feedback'

const cases = JSON.parse(fs.readFileSync(path.join(rootDir, 'test/fixtures/search-eval.json'), 'utf-8')) as EvalCase[]
const recipes = parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8'))
const result = evaluate(recipes, cases, defaultPantry)

if (process.argv.includes('--json')) {
  console.log(JSON.stringify(result, null, 2))
}
else if (!cases.length) {
  console.log('还没有评测样例。上线收集到筛选信号后，运行 pnpm feedback:pull 生成 test/fixtures/search-eval.json。')
}
else {
  console.log(`评测样例 ${result.cases} 组`)
  console.log(`点开的菜：共 ${result.opened.total} 次，前 10 命中 ${result.opened.hitAt10}，MRR ${result.opened.mrr}，已筛不出 ${result.opened.missing} 次`)
  console.log(`无结果的组合：共 ${result.empty.total} 次，现在仍无结果 ${result.empty.stillEmpty} 次`)
  if (result.worst.length) {
    console.log('\n排得最靠后或筛不出的点开记录：')
    for (const w of result.worst)
      console.log(`  ${w.stuff.join('、')} → id ${w.id}：${w.rank ? `第 ${w.rank} 位` : '筛不出'}（${w.count} 次）`)
  }
}
