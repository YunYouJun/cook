/**
 * 把图文做法的配图上传到腾讯云 COS（pnpm images:upload）
 *
 * 先运行 pnpm howtocook 生成 .cache/recipe-images，再上传 recipe-text.json 用到的配图。
 * 文件名按原图内容哈希，对象存储上已有的直接跳过；不删除旧图，旧版本 App 还可能在用。
 *
 * 环境变量：
 * - COS_SECRET_ID / COS_SECRET_KEY：子账号密钥，只授权这个桶 `COS_PREFIX` 下的 PutObject、HeadObject
 * - COS_BUCKET：桶名（含 APPID），例如 yunlefun-assets-1250000000
 * - COS_REGION：地域，例如 ap-shanghai
 * - COS_PREFIX：对象键前缀，默认 cook/，要和 recipe-text.json 的 imageBase 对应
 */
import type { RecipeTextData } from '../packages/cook/src/types/recipe-text'
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'
import COS from 'cos-nodejs-sdk-v5'
import { IMAGE_CACHE_CONTROL, IMAGE_CACHE_DIR } from './lib/recipe-images'

const { COS_SECRET_ID, COS_SECRET_KEY, COS_BUCKET, COS_REGION } = process.env
const prefix = process.env.COS_PREFIX ?? 'cook/'
if (!COS_SECRET_ID || !COS_SECRET_KEY || !COS_BUCKET || !COS_REGION) {
  console.error('缺少 COS_SECRET_ID、COS_SECRET_KEY、COS_BUCKET 或 COS_REGION')
  process.exit(1)
}

const data: RecipeTextData = JSON.parse(fs.readFileSync(new URL('../packages/data/recipe-text.json', import.meta.url), 'utf-8'))
if (data.imageBase && !data.imageBase.endsWith(`/${prefix}`))
  console.warn(`imageBase（${data.imageBase}）和上传前缀 ${prefix} 对不上，检查访问域名和 COS_PREFIX`)

const sources = [...new Set(Object.values(data.recipes).flatMap(text => text.blocks).flatMap(block => block.type === 'image' ? [block.src] : []))]
const missing = sources.filter(src => !fs.existsSync(path.join(IMAGE_CACHE_DIR, src)))
if (missing.length) {
  console.error(`${IMAGE_CACHE_DIR} 里缺少 ${missing.length} 张配图，先运行 pnpm howtocook <HowToCook 目录>`)
  process.exit(1)
}

const cos = new COS({ SecretId: COS_SECRET_ID, SecretKey: COS_SECRET_KEY })
const target = { Bucket: COS_BUCKET, Region: COS_REGION }

async function exists(key: string) {
  try {
    await cos.headObject({ ...target, Key: key })
    return true
  }
  catch (error) {
    if ((error as { statusCode?: number }).statusCode === 404)
      return false
    throw error
  }
}

let uploaded = 0
for (const src of sources) {
  const key = `${prefix}${src}`
  if (await exists(key))
    continue
  await cos.putObject({
    ...target,
    Key: key,
    Body: fs.readFileSync(path.join(IMAGE_CACHE_DIR, src)),
    ContentType: 'image/webp',
    CacheControl: IMAGE_CACHE_CONTROL,
  })
  uploaded++
  console.log(`上传 ${key}`)
}
console.log(`共 ${sources.length} 张配图，新上传 ${uploaded} 张`)
