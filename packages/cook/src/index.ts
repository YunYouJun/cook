#!/usr/bin/env npx tsx
import fs from 'node:fs'
import process from 'node:process'
import cac from 'cac'
import consola from 'consola'
import { version } from '../package.json'
import { runConvert } from './commands/convert.js'
import { runInfo } from './commands/info.js'
import { runIngredients } from './commands/ingredients.js'
import { runList } from './commands/list.js'
import { runRandom } from './commands/random.js'
import { runSearch } from './commands/search.js'
import { runStats } from './commands/stats.js'
import { envFile } from './utils/config.js'

const LINE_BREAK_RE = /\r?\n/

// 加载 .env 文件（Node 20.6+ 支持 --env-file，但这里手动加载兼容性更好）
function loadEnv() {
  try {
    if (!fs.existsSync(envFile))
      return
    const content = fs.readFileSync(envFile, 'utf-8')
    for (const line of content.split(LINE_BREAK_RE)) {
      const trimmed = line.trim()
      if (!trimmed || trimmed.startsWith('#'))
        continue
      const eqIndex = trimmed.indexOf('=')
      if (eqIndex === -1)
        continue
      const key = trimmed.slice(0, eqIndex).trim()
      const value = trimmed.slice(eqIndex + 1).trim()
      if (key && !(key in process.env)) {
        process.env[key] = value
      }
    }
  }
  catch {}
}

loadEnv()

const cli = cac('cook')

cli
  .command('convert', '将本地 CSV 数据转换为 JSON')
  .action(() => {
    runConvert()
  })

cli
  .command('fetch', '从飞书拉取菜谱数据并生成 CSV + JSON')
  .action(async () => {
    const { runFetch } = await import('./commands/fetch.js')
    await runFetch()
  })

cli
  .command('search', '根据食材/工具/关键词搜索菜谱')
  .option('--stuff <ingredients>', '食材，顿号分隔，如"土豆、番茄"')
  .option('--tool <tool>', '烹饪工具，如"电饭煲"')
  .option('--mode <mode>', '搜索模式: strict(严格)|loose(宽松)|survival(生存)', { default: 'loose' })
  .option('--keyword <keyword>', '关键词过滤菜名')
  .option('--json', '输出 JSON 格式')
  .option('--data-dir <dir>', '自定义数据目录')
  .action((options) => { runSearch(options) })

cli
  .command('random', '随机推荐菜谱')
  .option('--count <count>', '推荐数量', { default: 3 })
  .option('--json', '输出 JSON 格式')
  .option('--data-dir <dir>', '自定义数据目录')
  .action((options) => {
    const count = Number.parseInt(options.count, 10)
    if (!Number.isFinite(count) || count < 0) {
      consola.error(`无效的 --count 值: ${options.count}，请输入正整数。`)
      process.exit(1)
    }
    runRandom({ ...options, count })
  })

cli
  .command('list', '列出/筛选全部菜谱')
  .option('--keyword <keyword>', '菜名关键词')
  .option('--difficulty <level>', '按难度过滤: 简单|普通|困难')
  .option('--limit <n>', '最多显示条数（0=全部）', { default: 20 })
  .option('--json', '输出 JSON 格式')
  .option('--data-dir <dir>', '自定义数据目录')
  .action((options) => {
    const limit = Number.parseInt(options.limit, 10)
    if (!Number.isFinite(limit) || limit < 0) {
      consola.error(`无效的 --limit 值: ${options.limit}，请输入非负整数。`)
      process.exit(1)
    }
    runList({ ...options, limit })
  })

cli
  .command('ingredients', '列出所有可选食材和工具')
  .option('--category <cat>', '按类别过滤: vegetable|meat|staple|tool')
  .option('--json', '输出 JSON 格式')
  .action((options) => { runIngredients(options) })

cli
  .command('info <name>', '查看菜谱详情（模糊匹配菜名）')
  .option('--json', '输出 JSON 格式')
  .option('--data-dir <dir>', '自定义数据目录')
  .action((name, options) => { runInfo(name, options) })

cli
  .command('stats', '菜谱统计信息')
  .option('--json', '输出 JSON 格式')
  .option('--data-dir <dir>', '自定义数据目录')
  .action((options) => { runStats(options) })

cli.help()
cli.version(version)
cli.parse()
