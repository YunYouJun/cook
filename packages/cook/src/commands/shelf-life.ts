import process from 'node:process'
import consola from 'consola'
import { colors } from 'consola/utils'
import { lookupShelfLife } from '../utils/shelf-life.js'

export interface ShelfLifeCommandOptions {
  json?: boolean
}

export function runShelfLife(items: string[], opts: ShelfLifeCommandOptions = {}) {
  const result = lookupShelfLife(items)

  if (opts.json) {
    process.stdout.write(`${JSON.stringify(result, null, 2)}\n`)
    return
  }

  if (result.items.length === 0) {
    consola.info(`请提供要查询的食材（如 cook shelf-life 番茄,鸡蛋）；默认冷藏参考 ${result.defaultDays} 天`)
    return
  }

  consola.success('冰箱冷藏建议保质天数：\n')
  for (const item of result.items) {
    const aliasNote = item.input !== item.normalized ? colors.dim(`（${item.input} → ${item.normalized}）`) : ''
    const defaultNote = item.isDefault ? colors.dim(' [默认参考]') : ''
    consola.log(`  ${colors.bold(item.normalized)}${aliasNote}：${colors.green(`${item.days} 天`)}${defaultNote}`)
  }
}
