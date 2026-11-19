import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import process from 'node:process'
import COS from 'cos-nodejs-sdk-v5'

const { COS_SECRET_ID, COS_SECRET_KEY, COS_BUCKET, COS_REGION } = process.env
if (!COS_SECRET_ID || !COS_SECRET_KEY || !COS_BUCKET || !COS_REGION)
  throw new Error('缺少 COS 上传配置')

const manifest = JSON.parse(await readFile(new URL('../apps/ios/Cook/Resources/tomato-egg-clay.json', import.meta.url), 'utf8'))
const files = {
  image: { name: 'tomato-egg.webp', type: 'image/webp', extension: 'webp' },
  model: { name: 'TomatoEggAchievement.usdz', type: 'model/vnd.usdz+zip', extension: 'usdz' },
}
if (manifest.recipeID !== 250 || manifest.pairID !== 'tomato-egg-clay'
  || manifest.packs.length !== 2 || !['image', 'model'].every(kind => manifest.packs.some(pack => pack.kind === kind))) {
  throw new Error('番茄炒蛋的素材清单有误')
}

const uploads = await Promise.all(manifest.packs.map(async (pack) => {
  const file = files[pack.kind]
  const data = await readFile(new URL(`../assets/showcase/tomato-egg-clay/${file.name}`, import.meta.url))
  const digest = createHash('sha256').update(data).digest('hex')
  const key = `cook/showcase/250/tomato-egg-clay/${pack.kind}/v${pack.version}-${digest}.${file.extension}`
  if (!Number.isSafeInteger(pack.version) || pack.version < 1 || data.length !== pack.bytes
    || pack.bytes < 1 || pack.bytes > 8 * 1024 * 1024 || pack.sha256 !== digest
    || pack.url !== `https://assets.yunle.fun/${key}`) {
    throw new Error(`${pack.kind} 的本地文件与清单不一致`)
  }
  return { key, file, data }
}))

const cos = new COS({ SecretId: COS_SECRET_ID, SecretKey: COS_SECRET_KEY })
for (const { key, file, data } of uploads) {
  const target = { Bucket: COS_BUCKET, Region: COS_REGION, Key: key }
  try {
    await cos.headObject(target)
    console.log(`已发布 ${key}`)
    continue
  }
  catch (error) {
    if (error.statusCode !== 404)
      throw new Error(`无法检查 ${key}（${error.statusCode ?? error.code}）`)
  }
  try {
    await cos.putObject({
      ...target,
      Body: data,
      ContentType: file.type,
      CacheControl: 'public, max-age=31536000, immutable',
      Headers: { 'x-cos-forbid-overwrite': 'true' },
    })
    console.log(`已上传 ${key}`)
  }
  catch (error) {
    throw new Error(`无法上传 ${key}（${error.statusCode ?? error.code}）`)
  }
}
