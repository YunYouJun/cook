/**
 * 从 HowToCook 的本地仓库导入图文做法正文，生成 packages/data/recipe-text.json
 *
 * App（云食册）读这份数据离线显示做法，不用打开 GitHub；网页仍然跳转到 link。
 * recipe.csv 里 link 指向 HowToCook 的菜都会导入，增删这类菜或 HowToCook 更新后重新运行：
 *
 * git clone --depth 1 https://github.com/Anduin2017/HowToCook.git ../HowToCook
 * pnpm howtocook ../HowToCook
 */
import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { recipeCsvFile } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { HOWTOCOOK_REPO, howToCookPath, parseHowToCook } from './lib/howtocook'

const repo = process.argv[2]
if (!repo || !fs.existsSync(path.join(repo, 'dishes'))) {
  console.error('用法：pnpm howtocook <HowToCook 仓库目录>')
  process.exit(1)
}

const recipes = parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8'))
const data: RecipeTextData = {
  source: {
    name: 'HowToCook',
    url: HOWTOCOOK_REPO,
    license: 'Unlicense',
    commit: execFileSync('git', ['-C', repo, 'rev-parse', 'HEAD'], { encoding: 'utf-8' }).trim(),
  },
  recipes: {},
}

const missing: string[] = []
for (const recipe of recipes) {
  const file = howToCookPath(recipe.link)
  if (!file || recipe.id === undefined)
    continue
  const full = path.join(repo, file)
  if (!fs.existsSync(full)) {
    missing.push(`${recipe.id} ${recipe.name}：${file}`)
    continue
  }
  data.recipes[recipe.id] = parseHowToCook(fs.readFileSync(full, 'utf-8'))
}

const out = new URL('../packages/data/recipe-text.json', import.meta.url)
fs.writeFileSync(out, `${JSON.stringify(data, null, 2)}\n`)
console.log(`Wrote ${Object.keys(data.recipes).length} recipes to ${out.pathname}`)
if (missing.length) {
  console.error(`HowToCook 里找不到这些菜，可能改名或删除了，检查 recipe.csv 的 link：\n${missing.join('\n')}`)
  process.exit(1)
}
