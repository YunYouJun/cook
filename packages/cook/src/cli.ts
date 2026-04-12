#!/usr/bin/env node
import fs from 'node:fs'
import process from 'node:process'
import cac from 'cac'
import { version } from '../package.json'
import { runConvert } from './commands/convert'
import { envFile } from './utils/config'

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
    const { runFetch } = await import('./commands/fetch')
    await runFetch()
  })

cli
  .command('search', '检索菜谱（供 AI Skill 或脚本调用）')
  .option('--stuff <items>', '食材，逗号分隔（如 "鸡蛋,番茄"）')
  .option('--tool <tool>', '厨具（如 "电饭煲"）')
  .option('--difficulty <level>', '难度：简单/普通/困难')
  .option('--tag <tag>', '标签（如 "懒人"）')
  .option('--method <method>', '做法（如 "炒"）')
  .option('--limit <n>', '最大返回数（默认 10）')
  .option('--json', '输出原始 JSON（供 AI/脚本消费）')
  .action(async (opts) => {
    const { runSearch } = await import('./commands/search')
    runSearch(opts)
  })

cli.help()
cli.version(version)
cli.parse()
