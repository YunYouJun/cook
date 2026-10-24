/**
 * 从 HowToCook 的本地仓库导入图文做法正文，生成 packages/data/recipe-text.json
 *
 * App（云食册）读这份数据离线显示做法，不用打开 GitHub；网页仍然跳转到 link。
 * recipe.csv 里 link 指向 HowToCook 的菜都会导入，增删这类菜或 HowToCook 更新后重新运行：
 *
 * git clone --depth 1 https://github.com/Anduin2017/HowToCook.git ../HowToCook
 * pnpm howtocook ../HowToCook
 *
 * 配图压缩后写到 .cache/recipe-images，再用 pnpm images:upload 传到对象存储。
 * imageBase 沿用 recipe-text.json 里原来的值，换地址时设置 COOK_IMAGE_BASE。
 */
import type { RecipeTextBlock, RecipeTextData } from '../packages/cook/src/types/recipe-text'
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import { recipeCsvFile } from '../packages/cook/src/utils/config'
import { parseRecipeCsv } from '../packages/cook/src/utils/csv'
import { HOWTOCOOK_REPO, howToCookPath, parseHowToCook } from './lib/howtocook'
import { convertImage, IMAGE_CACHE_DIR } from './lib/recipe-images'
import { validateAmountAnnotations } from './lib/validate-recipe-amounts'

const repo = process.argv[2]
if (!repo || !fs.existsSync(path.join(repo, 'dishes'))) {
  console.error('用法：pnpm howtocook <HowToCook 仓库目录>')
  process.exit(1)
}

const out = new URL('../packages/data/recipe-text.json', import.meta.url)
const previous: Partial<RecipeTextData> = fs.existsSync(out) ? JSON.parse(fs.readFileSync(out, 'utf-8')) : {}
const imageBase = process.env.COOK_IMAGE_BASE ?? previous.imageBase
if (imageBase && !/^https:\/\/\S+\/$/.test(imageBase)) {
  console.error(`配图地址前缀应为 https:// 开头、/ 结尾：${imageBase}`)
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
  ...(imageBase ? { imageBase } : {}),
  recipes: {},
}

fs.mkdirSync(path.join(IMAGE_CACHE_DIR, 'howtocook'), { recursive: true })
const skippedImages: string[] = []

function safeDecode(src: string) {
  try {
    return decodeURIComponent(src)
  }
  catch {
    return src
  }
}

/** 把 Markdown 里的相对图片换成压缩后的配图；外链图片不收（来源不稳定，也没法缓存） */
async function resolveImages(file: string, blocks: ReturnType<typeof parseHowToCook>) {
  const resolved: RecipeTextBlock[] = []
  for (const block of blocks) {
    if (block.type !== 'image') {
      resolved.push(block)
      continue
    }
    const source = path.join(repo!, path.dirname(file), safeDecode(block.src))
    if (/^https?:/.test(block.src) || !fs.existsSync(source)) {
      skippedImages.push(`${file}：${block.src}`)
      continue
    }
    const image = await convertImage(fs.readFileSync(source))
    fs.writeFileSync(path.join(IMAGE_CACHE_DIR, image.src), image.data)
    resolved.push({ type: 'image', src: image.src, width: image.width, height: image.height, ...(block.alt ? { alt: block.alt } : {}) })
  }
  return resolved
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
  data.recipes[recipe.id] = { blocks: await resolveImages(file, parseHowToCook(fs.readFileSync(full, 'utf-8'))) }
}

fs.writeFileSync(out, `${JSON.stringify(data, null, 2)}\n`)
const images = Object.values(data.recipes).flatMap(text => text.blocks).filter(block => block.type === 'image').length
console.log(`Wrote ${Object.keys(data.recipes).length} recipes and ${images} images (${IMAGE_CACHE_DIR}) to ${out.pathname}`)
if (skippedImages.length)
  console.warn(`没有收录的配图（外链或找不到文件）：\n${skippedImages.join('\n')}`)
if (missing.length) {
  console.error(`HowToCook 里找不到这些菜，可能改名或删除了，检查 recipe.csv 的 link：\n${missing.join('\n')}`)
  process.exit(1)
}

// 独立标注不能在导入时自动重新签署来源。先保存完整正文，再显式报告失配。
const amountsFile = new URL('../packages/data/recipe-amounts.json', import.meta.url)
if (fs.existsSync(amountsFile)) {
  const errors = validateAmountAnnotations(JSON.parse(fs.readFileSync(amountsFile, 'utf8')), data, new Set(recipes.map(recipe => recipe.id!)))
  if (errors.length) {
    console.error(`用量标注需重新核对（应用会保守回退原文）：\n${errors.join('\n')}`)
    process.exitCode = 1
  }
}
