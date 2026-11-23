import { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import process from 'node:process'
import COS from 'cos-nodejs-sdk-v5'

const { COS_SECRET_ID, COS_SECRET_KEY, COS_BUCKET, COS_REGION } = process.env
const [mode, ...extraArgs] = process.argv.slice(2)
if (extraArgs.length || (mode !== undefined && !['--check', '--verify-public'].includes(mode))) {
  throw new Error('用法：node scripts/upload-showcase-assets.mjs [--check | --verify-public]')
}

const manifests = JSON.parse(await readFile(new URL('../apps/ios/Cook/Resources/showcase-packs.json', import.meta.url), 'utf8'))
const androidModels = JSON.parse(await readFile(new URL('../assets/android/models.json', import.meta.url), 'utf8'))
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
  const { recipeID, pairID, packs, androidModel, badgeName } = manifest
  if (!Number.isSafeInteger(recipeID) || recipeID < 1 || typeof pairID !== 'string'
    || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(pairID) || !Array.isArray(packs)
    || packs.length !== 2 || !Object.keys(files).every(kind => packs.some(pack => pack.kind === kind))) {
    throw new Error(`菜品 ${recipeID} 的素材清单有误`)
  }
  if (androidModel !== undefined && androidModel?.kind !== 'model')
    throw new Error(`菜品 ${recipeID} 的 Android 模型清单有误`)
  if (badgeName !== undefined && !badges.includes(badgeName))
    throw new Error(`菜品 ${recipeID} 的徽章有误`)
  const inputs = packs.map(pack => ({ pack, file: files[pack.kind] }))
  if (androidModel)
    inputs.push({ pack: androidModel, file: { name: 'model.glb', type: 'model/gltf-binary' } })

  return inputs.map(async ({ pack, file }) => {
    const { version, bytes, sha256, kind, url } = pack
    const extension = file.name.split('.').at(-1)
    const key = `cook/showcase/${recipeID}/${pairID}/${kind}/v${version}-${sha256}.${extension}`
    if (!Number.isSafeInteger(version) || version < 1 || !Number.isSafeInteger(bytes)
      || bytes < 1 || bytes > 8 * 1024 * 1024 || !/^[a-f0-9]{64}$/.test(sha256)
      || url !== `https://assets.yunle.fun/${key}`) {
      throw new Error(`${pairID} 的 ${kind} 素材清单有误`)
    }
    const isGlb = extension === 'glb'
    const source = isGlb ? androidModels[`showcase/${pairID}/model.glb`] : undefined
    if (isGlb && (source?.file !== `${pairID}-${sha256}.glb` || source.bytes !== bytes || source.sha256 !== sha256)) {
      throw new Error(`${pairID} 的展柜模型与 Android 构建清单不一致`)
    }
    let data
    try {
      data = await readFile(new URL(`../assets/showcase/${pairID}/${file.name}`, import.meta.url))
    }
    catch (error) {
      if (error.code !== 'ENOENT' || !source)
        throw error
      data = await readVerifiedPublicObject(`cook/android/${source.file}`, bytes, sha256)
    }
    if (isGlb && (data.length < 20 || data.toString('ascii', 0, 4) !== 'glTF'
      || data.readUInt32LE(4) !== 2 || data.readUInt32LE(8) !== data.length)) {
      throw new Error(`${pairID} 的 Android 模型不是有效的 GLB 2.0 文件`)
    }
    if (data.length !== bytes || createHash('sha256').update(data).digest('hex') !== sha256) {
      throw new Error(`${pairID} 的 ${kind} 文件与清单不一致`)
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

const objects = [...uploads, ...imageUploads]
if (mode === '--check') {
  console.log(`已校验 ${objects.length} 份展柜素材`)
  process.exit(0)
}
if (mode === '--verify-public') {
  const results = await Promise.allSettled(objects.map(verifyPublicObject))
  const failures = results.flatMap((result, index) => {
    if (result.status === 'fulfilled')
      return []
    return [`${objects[index].key}：${result.reason.message}`]
  })
  if (failures.length) {
    console.error(failures.join('\n'))
    process.exit(1)
  }
  console.log(`已从公网校验 ${objects.length} 份展柜素材`)
  process.exit(0)
}
if (!COS_SECRET_ID || !COS_SECRET_KEY || !COS_BUCKET || !COS_REGION)
  throw new Error('缺少 COS 上传配置')

const cos = new COS({ SecretId: COS_SECRET_ID, SecretKey: COS_SECRET_KEY })
for (const { key, type, data } of objects) {
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

async function verifyPublicObject({ key, data }) {
  await readVerifiedPublicObject(key, data.length, createHash('sha256').update(data).digest('hex'))
}

async function readVerifiedPublicObject(key, bytes, sha256) {
  const response = await fetch(`https://assets.yunle.fun/${key}`, {
    redirect: 'error',
    headers: { 'Accept-Encoding': 'identity' },
    signal: AbortSignal.timeout(10_000),
  })
  if (response.status !== 200 || !response.body) {
    await response.body?.cancel()
    throw new Error(`HTTP ${response.status}`)
  }
  const hash = createHash('sha256')
  const chunks = []
  let received = 0
  for await (const chunk of response.body) {
    received += chunk.byteLength
    if (received > bytes)
      throw new Error('公网文件超过清单大小')
    chunks.push(chunk)
    hash.update(chunk)
  }
  if (received !== bytes || hash.digest('hex') !== sha256) {
    throw new Error('公网文件与预期大小或摘要不一致')
  }
  return Buffer.concat(chunks, received)
}
