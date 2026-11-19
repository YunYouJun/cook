import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import process from 'node:process'
import COS from 'cos-nodejs-sdk-v5'

const { COS_SECRET_ID, COS_SECRET_KEY, COS_BUCKET, COS_REGION } = process.env
if (!COS_SECRET_ID || !COS_SECRET_KEY || !COS_BUCKET || !COS_REGION)
  throw new Error('缺少 COS 上传配置')

const manifests = JSON.parse(await readFile(new URL('../apps/ios/Cook/Resources/showcase-packs.json', import.meta.url), 'utf8'))
const files = {
  image: { name: 'image.webp', type: 'image/webp' },
  model: { name: 'model.usdz', type: 'model/vnd.usdz+zip' },
}
const badges = ['smashed-cucumber', 'steamed-egg', 'tomato-egg']
const sourceImages = [
  ...badges.map(name => ({
    path: `../apps/ios/Cook/Resources/cooking-badge-${name}.webp`,
    keyPrefix: `cook/showcase/badges/${name}`,
  })),
  {
    path: '../docs/scripts/showcase/tomato-cut-r2.webp',
    keyPrefix: 'cook/showcase/source/tomato-cut-r2',
  },
]
if (!Array.isArray(manifests) || !manifests.length
  || new Set(manifests.map(manifest => manifest.recipeID)).size !== manifests.length
  || new Set(manifests.map(manifest => manifest.pairID)).size !== manifests.length) {
  throw new Error('素材清单缺失或菜品重复')
}

const uploads = await Promise.all(manifests.flatMap((manifest) => {
  const { recipeID, pairID, packs } = manifest
  if (!Number.isSafeInteger(recipeID) || recipeID < 1 || typeof pairID !== 'string'
    || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(pairID) || !Array.isArray(packs)
    || packs.length !== 2 || !Object.keys(files).every(kind => packs.some(pack => pack.kind === kind))) {
    throw new Error(`菜品 ${recipeID} 的素材清单有误`)
  }
  return packs.map(async (pack) => {
    const file = files[pack.kind]
    const data = await readFile(new URL(`../assets/showcase/${pairID}/${file.name}`, import.meta.url))
    const digest = createHash('sha256').update(data).digest('hex')
    const extension = file.name.split('.').at(-1)
    const key = `cook/showcase/${recipeID}/${pairID}/${pack.kind}/v${pack.version}-${digest}.${extension}`
    if (!Number.isSafeInteger(pack.version) || pack.version < 1 || data.length !== pack.bytes
      || pack.bytes < 1 || pack.bytes > 8 * 1024 * 1024 || pack.sha256 !== digest
      || pack.url !== `https://assets.yunle.fun/${key}`) {
      throw new Error(`${pairID} 的 ${pack.kind} 文件与清单不一致`)
    }
    return { key, type: file.type, data }
  })
}))

const imageUploads = await Promise.all(sourceImages.map(async ({ path, keyPrefix }) => {
  const data = await readFile(new URL(path, import.meta.url))
  if (data.toString('ascii', 0, 4) !== 'RIFF' || data.toString('ascii', 8, 12) !== 'WEBP')
    throw new Error(`${path} 不是有效的 WebP 文件`)
  const digest = createHash('sha256').update(data).digest('hex')
  return { key: `${keyPrefix}-${digest}.webp`, type: 'image/webp', data }
}))

const cos = new COS({ SecretId: COS_SECRET_ID, SecretKey: COS_SECRET_KEY })
for (const { key, type, data } of [...uploads, ...imageUploads]) {
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
      ContentType: type,
      CacheControl: 'public, max-age=31536000, immutable',
      Headers: { 'x-cos-forbid-overwrite': 'true' },
    })
    console.log(`已上传 ${key}`)
  }
  catch (error) {
    throw new Error(`无法上传 ${key}（${error.statusCode ?? error.code}）`)
  }
}
