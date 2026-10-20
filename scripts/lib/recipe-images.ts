/**
 * 图文做法的配图：压缩成 webp，按原图内容哈希命名
 *
 * 文件名只取决于原图内容，所以导入多少次、在哪台机器上导入，同一张图的地址都不变，
 * 对象存储上可以设成永久缓存；原图换了内容就是新地址，不会读到旧缓存。
 */
import type { Buffer } from 'node:buffer'
import { createHash } from 'node:crypto'
import sharp from 'sharp'

/** 手机上全屏宽度够用，再大只是浪费流量 */
export const MAX_IMAGE_WIDTH = 1080
export const IMAGE_QUALITY = 75
/** 本地生成的配图目录，`pnpm images:upload` 从这里上传 */
export const IMAGE_CACHE_DIR = '.cache/recipe-images'
/** 对象存储上配图不会变，浏览器和 App 可以一直缓存 */
export const IMAGE_CACHE_CONTROL = 'public, max-age=31536000, immutable'

export interface ConvertedImage {
  /** 相对 imageBase 的路径 */
  src: string
  width: number
  height: number
  data: Buffer
}

export function imageKey(source: Buffer, folder = 'howtocook') {
  return `${folder}/${createHash('sha256').update(source).digest('hex').slice(0, 16)}.webp`
}

export async function convertImage(source: Buffer, folder?: string): Promise<ConvertedImage> {
  const { data, info } = await sharp(source)
    // 按 EXIF 方向转正，手机拍的照片才不会横着
    .rotate()
    .resize({ width: MAX_IMAGE_WIDTH, withoutEnlargement: true })
    .webp({ quality: IMAGE_QUALITY })
    .toBuffer({ resolveWithObject: true })
  return { src: imageKey(source, folder), width: info.width, height: info.height, data }
}
