import process from 'node:process'
import consola from 'consola'
import { colors } from 'consola/utils'
import { loadCookCatalog } from '../utils/agent-catalog.js'
import { findIncompatibleRules } from '../utils/incompatible.js'
import { uniqueStuff } from '../utils/recommend.js'

export interface IncompatibleCommandOptions {
  json?: boolean
}

export function runIncompatible(items: string[], opts: IncompatibleCommandOptions = {}) {
  const catalog = loadCookCatalog()
  const normalized = uniqueStuff(items)
  const warnings = findIncompatibleRules(normalized, catalog.incompatible)

  if (opts.json) {
    process.stdout.write(`${JSON.stringify({ ingredients: normalized, warnings }, null, 2)}\n`)
    return
  }

  if (normalized.length < 2) {
    consola.info('请至少提供两种食材，如：cook incompatible 牛奶,韭菜')
    return
  }

  if (warnings.length === 0) {
    consola.success(`未在食物相克库中发现冲突：${normalized.join('、')}`)
    return
  }

  for (const w of warnings) {
    consola.warn(`⚠️ ${colors.yellow(`${w.foodA} + ${w.foodB}`)}：${w.reason}`)
  }
}
