/**
 * 巡检 app/data/recipe.csv 中的 B 站视频是否失效，输出 Markdown 报告
 *
 * pnpm check:videos                       # 打印报告
 * pnpm check:videos --out report.md       # 同时写入文件（供 workflow 更新 issue）
 */
import fs from 'node:fs'
import process from 'node:process'
import { setTimeout as sleep } from 'node:timers/promises'
import { bilibiliViewApi, classifyBilibiliCode } from '../packages/cook/src/utils/bilibili'
import { recipeCsvFile } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'

const DELAY_MS = 400

async function check(id: string) {
  try {
    const res = await fetch(bilibiliViewApi(id), {
      headers: { 'User-Agent': 'Mozilla/5.0 (cook recipe link checker; +https://github.com/YunYouJun/cook)' },
      signal: AbortSignal.timeout(15_000),
    })
    const body = await res.json() as { code: number, message?: string }
    return { status: classifyBilibiliCode(body.code), detail: `${body.code} ${body.message ?? ''}`.trim() }
  }
  catch (e) {
    return { status: 'unknown' as const, detail: (e as Error).message }
  }
}

const recipes = parseRecipeCsv(fs.readFileSync(recipeCsvFile, 'utf-8'))
const byVideo = new Map<string, { line: number, name: string }[]>()
recipes.forEach((recipe, i) => {
  if (!recipe.bv)
    return
  const list = byVideo.get(recipe.bv) ?? []
  list.push({ line: i + 2, name: recipe.name })
  byVideo.set(recipe.bv, list)
})

const dead: string[] = []
let unknown = 0
for (const [id, uses] of byVideo) {
  const result = await check(id)
  if (result.status === 'dead') {
    for (const use of uses)
      dead.push(`| ${use.line} | ${use.name} | [${id}](https://www.bilibili.com/video/${id}) | ${result.detail} |`)
  }
  else if (result.status === 'unknown') {
    unknown++
  }
  await sleep(DELAY_MS)
}

const report = [
  `共检查 ${byVideo.size} 个视频，失效 ${dead.length} 条菜谱，无法确认 ${unknown} 个（接口限流或超时，下次巡检会重试）。`,
  '',
  ...(dead.length
    ? ['| 行 | 菜名 | 视频 | 接口返回 |', '| --- | --- | --- | --- |', ...dead]
    : ['没有发现失效视频。']),
  '',
  `_由 \`pnpm check:videos\` 生成于 ${new Date().toISOString().slice(0, 10)}。替换视频号或删除菜谱后，下一次巡检会自动更新此列表。_`,
].join('\n')

console.log(report)
const outIndex = process.argv.indexOf('--out')
if (outIndex > -1 && process.argv[outIndex + 1])
  fs.writeFileSync(process.argv[outIndex + 1]!, report)
