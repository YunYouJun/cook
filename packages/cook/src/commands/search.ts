import type { SearchOptions } from '../utils/search.js'
import fs from 'node:fs'
import consola from 'consola'
import { colors } from 'consola/utils'
import {
  incompatibleFoodsCsvFile,
  recipeCsvFile,
} from '../utils/config.js'
import { parseIncompatibleFoodsCsv, parseRecipeCsv } from '../utils/csv.js'
import { searchRecipes } from '../utils/search.js'

export type { SearchOptions }

export interface SearchCommandOptions {
  stuff?: string
  tool?: string
  difficulty?: string
  tag?: string
  method?: string
  limit?: number
  /** 输出原始 JSON（供 AI/脚本消费） */
  json?: boolean
}

const STUFF_SPLIT_RE = /[,，、]/

export function runSearch(opts: SearchCommandOptions) {
  const recipeCsv = fs.readFileSync(recipeCsvFile, 'utf-8')
  const recipes = parseRecipeCsv(recipeCsv)

  let incompatible: ReturnType<typeof parseIncompatibleFoodsCsv> = []
  try {
    const incompatibleCsv = fs.readFileSync(incompatibleFoodsCsvFile, 'utf-8')
    incompatible = parseIncompatibleFoodsCsv(incompatibleCsv)
  }
  catch {
    consola.warn('未找到食物相克数据，跳过相克检查')
  }

  const options: SearchOptions = {
    stuff: opts.stuff?.split(STUFF_SPLIT_RE).filter(Boolean),
    tool: opts.tool,
    difficulty: opts.difficulty,
    tag: opts.tag,
    method: opts.method,
    limit: opts.limit ?? 10,
  }

  const result = searchRecipes(recipes, incompatible, options)

  // JSON 模式：供 AI Skill / 脚本消费
  if (opts.json) {
    consola.log(JSON.stringify(result, null, 2))
    return
  }

  // 美化输出模式
  if (result.recipes.length === 0) {
    consola.info('未找到匹配的菜谱')
    return
  }

  consola.success(`找到 ${colors.green(String(result.recipes.length))} 道菜谱：\n`)

  for (const r of result.recipes) {
    const difficulty = r.difficulty ? colors.dim(` · ${r.difficulty}`) : ''
    const tags = r.tags?.length ? colors.dim(` · ${r.tags.join('、')}`) : ''
    consola.log(`  ${colors.bold(r.name)}${difficulty}${tags}`)

    const stuffStr = r.stuff.map((s) => {
      return r.matchedStuff.includes(s)
        ? colors.green(s)
        : colors.dim(s)
    }).join('、')
    consola.log(`    食材：${stuffStr}`)

    if (r.matchedStuff.length > 0 && r.missingStuff.length > 0) {
      consola.log(`    ${colors.green(`✓ 你有 ${r.matchedStuff.length} 样`)}${colors.yellow(`，还需 ${r.missingStuff.join('、')}`)}`)
    }
    else if (r.missingStuff.length === 0) {
      consola.log(`    ${colors.green('✓ 食材全部齐全')}`)
    }

    if (r.videoUrl) {
      consola.log(`    ${colors.cyan(`📺 ${r.videoUrl}`)}`)
    }
    consola.log('')
  }

  if (result.warnings.length > 0) {
    for (const w of result.warnings) {
      consola.warn(`⚠️ ${colors.yellow(`${w.foodA} + ${w.foodB}`)}：${w.reason}`)
    }
  }
}
